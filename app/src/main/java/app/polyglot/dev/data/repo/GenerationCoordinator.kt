package app.polyglot.dev.data.repo

import app.polyglot.dev.data.local.MessageStatus
import app.polyglot.dev.data.net.ErrorKind
import app.polyglot.dev.data.net.ChatStreamClient
import app.polyglot.dev.data.net.ChatMessage
import app.polyglot.dev.data.net.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正在进行的生成请求 */
data class ActiveGeneration(
    val requestId: String,
    val sessionId: String,
    val messageId: String,
    val configId: String?
)

/** 一次生成的请求参数：消息记录已经落盘，这里只描述怎么发这次请求 */
data class GenerationRequest(
    val requestId: String,
    val sessionId: String,
    val assistantMessageId: String,
    val assistantSeq: Int,
    /** 会话所属配置 id，同时也是 providers 表的主键 */
    val configId: String?,
    val modelId: String,
    val initialContent: String,
    /** 重试时首次收到正文要丢掉旧内容；发送新消息时为 false */
    val replaceOnFirstDelta: Boolean
)

/**
 * 生成任务协调器。
 *
 * 职责：
 * 1. 同一时间只允许一个生成请求，连点、重试、换模型都不会并行发第二次；
 * 2. 流式增量按顺序批量落盘，界面从数据库 Flow 读取，**未提交的片段不会显示成已保存正文**；
 * 3. 明确区分正常结束、被截断、失败、用户停止和退到后台中断；
 * 4. 启动恢复完成前拒绝开始新的生成。
 *
 * 任务本身跑在应用作用域上，界面重建（旋转、切页）不会打断或重复请求；
 * 进程死亡后不会继续运行，恢复完全依赖已经提交到磁盘的数据。
 */
class GenerationCoordinator(
    private val repo: ChatRepository,
    private val configRepo: ConfigRepository,
    private val client: ChatStreamClient,
    private val networkAvailable: () -> Boolean,
    private val scope: CoroutineScope
) {

    private enum class StopReason { NONE, USER, LIFECYCLE }

    private val _active = MutableStateFlow<ActiveGeneration?>(null)
    val active: StateFlow<ActiveGeneration?> = _active

    val generating: StateFlow<Boolean> = _active
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _recovered = MutableStateFlow(false)

    /** 启动恢复完成前为 false，此时不允许开始新生成 */
    val recovered: StateFlow<Boolean> = _recovered

    /**
     * 最近一次启动恢复的结果。界面可能晚于恢复流程创建，
     * 读取这里就能拿到仍有效的撤销机会及其剩余时间，不必重复查询数据库。
     */
    private val _lastRecovery = MutableStateFlow<LaunchRecovery?>(null)
    val lastRecovery: StateFlow<LaunchRecovery?> = _lastRecovery

    /** 需要在界面提示的存储类错误（写盘失败等） */
    private val _storageErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val storageErrors: SharedFlow<String> = _storageErrors

    private var job: Job? = null

    @Volatile
    private var stopReason = StopReason.NONE

    // ---------- 启动恢复 ----------

    /** 应用启动时调用一次：遗留“生成中”改为“已中断”，返回仍有效的撤销机会 */
    suspend fun recoverOnLaunch(): LaunchRecovery {
        val result = repo.recoverOnLaunch()
        _lastRecovery.value = result
        _recovered.value = true
        return result
    }

    // ---------- 生命周期 ----------

    /** 用户点击停止：取消请求并把已收到内容标记为“已停止” */
    fun stopByUser() {
        if (_active.value == null) return
        stopReason = StopReason.USER
        job?.cancel()
    }

    /** 切到其他 App 或锁屏：保留部分内容并标记“已中断”，不做后台持续生成 */
    fun interruptForLifecycle() {
        if (_active.value == null) return
        stopReason = StopReason.LIFECYCLE
        job?.cancel()
    }

    // ---------- 启动一次生成 ----------

    /** 返回 false 表示被拒绝：恢复未完成，或已有请求在跑（连点不会产生第二次请求） */
    fun start(request: GenerationRequest): Boolean {
        if (!_recovered.value) return false
        val active = ActiveGeneration(
            requestId = request.requestId,
            sessionId = request.sessionId,
            messageId = request.assistantMessageId,
            configId = request.configId
        )
        if (!_active.compareAndSet(null, active)) return false
        stopReason = StopReason.NONE
        job = scope.launch {
            try {
                run(request)
            } finally {
                _active.value = null
                stopReason = StopReason.NONE
                job = null
            }
        }
        return true
    }

    private suspend fun run(request: GenerationRequest) {
        // 网络预检：明确无网络时不假装开始过
        if (!networkAvailable()) {
            finish(request, request.initialContent, MessageStatus.FAILED, ErrorKind.NO_NETWORK.name)
            return
        }

        val provider = configRepo.providerById(request.configId ?: "")
        val apiKey = provider?.let { configRepo.readApiKey(it.id) }
        if (provider == null || apiKey.isNullOrBlank()) {
            finish(request, request.initialContent, MessageStatus.FAILED, ErrorKind.AUTH.name)
            return
        }

        // 请求的 model 参数不会自动成为模型可见的对话内容。
        // 每次发送和重试都用本次请求的模型 ID 构造身份，不写入或复制进聊天记录。
        val identity = ChatMessage(
            role = "system",
            content = "本次会话由应用选择调用的具体模型 ID 是：${request.modelId}。" +
                "当用户询问你是哪个模型、模型名称或版本时，请明确说明当前调用的模型 ID，" +
                "不要只回答厂家或模型系列名称。历史对话可能来自其他模型，" +
                "其中的自我介绍不代表你当前的模型身份。" +
                "此 ID 是应用请求的模型标识；若它是服务端别名，不要推测未提供的底层版本。"
        )
        val context = listOf(identity) + repo.buildContext(
            repo.messagesOf(request.sessionId),
            beforeSeqExclusive = request.assistantSeq
        )

        val sb = StringBuilder(request.initialContent)
        var replacePending = request.replaceOnFirstDelta
        // 最后一次成功提交的正文：写盘出问题时只能保留到这里
        var lastCommitted = request.initialContent
        var lastCommitAt = System.currentTimeMillis()
        var done = false
        var failure: StreamEvent.Failure? = null

        try {
            client.streamChat(provider.baseUrl, apiKey, request.modelId, context).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        // 重试场景：旧内容保留到新正文第一批提交为止
                        if (replacePending) {
                            sb.setLength(0)
                            replacePending = false
                        }
                        sb.append(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastCommitAt >= commitInterval(sb.length)) {
                            val text = sb.toString()
                            try {
                                repo.commitAssistant(
                                    request.assistantMessageId, text, MessageStatus.GENERATING, null
                                )
                                lastCommitted = text
                                lastCommitAt = now
                            } catch (e: Exception) {
                                // 写盘失败：立即停止请求，保留最后一次可靠提交的数据
                                reportStorageFailure(e)
                                throw StreamAborted(e)
                            }
                        }
                    }
                    StreamEvent.Done -> done = true
                    // 截断不单独记状态：没收到结束标记就落到下面的终局判断
                    is StreamEvent.Truncated -> Unit
                    is StreamEvent.Failure -> failure = event
                }
            }
        } catch (ce: CancellationException) {
            val status = if (stopReason == StopReason.USER) {
                MessageStatus.STOPPED
            } else {
                MessageStatus.INTERRUPTED
            }
            withContext(NonCancellable) {
                runCatching { repo.commitAssistant(request.assistantMessageId, sb.toString(), status, null) }
                    .onFailure { reportStorageFailure(it) }
            }
            throw ce
        } catch (e: StreamAborted) {
            withContext(NonCancellable) {
                runCatching {
                    repo.commitAssistant(
                        request.assistantMessageId, lastCommitted, MessageStatus.FAILED,
                        ErrorKind.UNKNOWN.name
                    )
                }
            }
            return
        } catch (e: Exception) {
            withContext(NonCancellable) {
                runCatching {
                    repo.commitAssistant(
                        request.assistantMessageId, sb.toString(), MessageStatus.FAILED,
                        ErrorKind.UNKNOWN.name
                    )
                }
            }
            return
        }

        // 终局写入不可取消：走到这里说明正文已经收完，不能因为取消丢掉结论
        withContext(NonCancellable) {
            val status: String
            val errorKind: String?
            if (failure != null) {
                status = MessageStatus.FAILED
                errorKind = failure.kind.name
            } else if (done) {
                status = MessageStatus.DONE
                errorKind = null
            } else {
                // 没有结束标记，正文可能不完整，不能标记为完成
                status = MessageStatus.TRUNCATED
                errorKind = ErrorKind.INTERRUPTED_CONN.name
            }
            runCatching {
                repo.commitAssistant(request.assistantMessageId, sb.toString(), status, errorKind)
            }.onFailure { reportStorageFailure(it) }
        }
    }

    private suspend fun finish(
        request: GenerationRequest,
        content: String,
        status: String,
        errorKind: String?
    ) {
        withContext(NonCancellable) {
            runCatching { repo.commitAssistant(request.assistantMessageId, content, status, errorKind) }
                .onFailure { reportStorageFailure(it) }
        }
    }

    private fun reportStorageFailure(e: Throwable) {
        _storageErrors.tryEmit("保存失败，磁盘可能已满。已保留最后一次成功保存的内容")
    }

    /** 写盘失败时用来打断流式收集 */
    private class StreamAborted(cause: Throwable) : Exception(cause)

    /**
     * 提交间隔随正文长度放宽：短回复保持接近实时的观感，
     * 长回复避免反复重写整段文本带来平方级的写入量。
     */
    private fun commitInterval(length: Int): Long = when {
        length < 2_000 -> 120L
        length < 10_000 -> 250L
        length < 40_000 -> 500L
        else -> 1_000L
    }
}

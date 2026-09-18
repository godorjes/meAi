package app.polyglot.dev.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.polyglot.dev.PolyglotApp
import app.polyglot.dev.data.local.MessageEntity
import app.polyglot.dev.data.local.MessageStatus
import app.polyglot.dev.data.local.ProviderEntity
import app.polyglot.dev.data.local.Role
import app.polyglot.dev.data.local.SessionEntity
import app.polyglot.dev.data.net.ErrorKind
import app.polyglot.dev.data.repo.ChatRepository
import app.polyglot.dev.data.repo.ConfigRepository
import app.polyglot.dev.data.repo.DraftSaveState
import app.polyglot.dev.data.repo.DraftWriter
import app.polyglot.dev.data.repo.GenerationCoordinator
import app.polyglot.dev.data.repo.GenerationRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** 换模型弹层中的一个可选项：某配置下的具体模型 */
data class ModelTarget(
    val configId: String,
    val configName: String,
    val modelId: String
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PolyglotApp).container
    private val chatRepo: ChatRepository = container.chatRepository
    private val configRepo: ConfigRepository = container.configRepository
    private val prefs = container.prefs
    private val draftWriter: DraftWriter = container.draftWriter
    private val coordinator: GenerationCoordinator = container.generationCoordinator

    // ---------- 会话与消息 ----------

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    @OptIn(ExperimentalCoroutinesApi::class)
    val session: StateFlow<SessionEntity?> = _currentSessionId
        .flatMapLatest { id -> if (id == null) flowOf(null) else chatRepo.observeSession(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> = _currentSessionId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chatRepo.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val providers: StateFlow<List<ProviderEntity>> = configRepo.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---------- 抽屉列表与搜索 ----------

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val drawerSessions: StateFlow<List<SessionEntity>> =
        combine(chatRepo.activeSessions, _searchQuery) { list, q ->
            if (q.isBlank()) list else list.filter { it.title.contains(q.trim(), ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearch(q: String) { _searchQuery.value = q }

    // ---------- 草稿 ----------

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft

    /** 启动恢复是否完成：完成前不允许开始新的生成 */
    val ready: StateFlow<Boolean> = coordinator.recovered

    fun updateDraft(text: String) {
        _draft.value = text
        // 不再取消上一次任务并等待 400ms：直接排队，由应用级流水线按顺序尽快落盘
        draftWriter.submit(_currentSessionId.value, text)
    }

    // ---------- 生成状态 ----------

    val generating: StateFlow<Boolean> = coordinator.generating

    /** 正在生成的会话 id（用于删除拦截、换模型提示） */
    val generatingSessionId: StateFlow<String?> = coordinator.active
        .map { it?.sessionId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ---------- 空会话的待选模型 ----------

    private data class PendingModel(val configId: String, val modelId: String, val snapshot: String)

    private val _pending = MutableStateFlow<PendingModel?>(null)

    /** 顶栏展示的模型名：当前会话模型 > 待选模型 > 第一个可用配置 */
    val displayedModelLabel: StateFlow<String?> =
        combine(session, providers, _pending) { s, ps, p ->
            when {
                s != null -> s.modelNameSnapshot
                p != null -> p.snapshot
                ps.isNotEmpty() -> ps.first().let { "${it.name} · ${it.selectedModelId}" }
                else -> null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ---------- 删除撤销 ----------

    private val _pendingDelete = MutableStateFlow<SessionEntity?>(null)
    val pendingDelete: StateFlow<SessionEntity?> = _pendingDelete
    private var undoJob: Job? = null

    // ---------- 提示 ----------

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    fun clearToast() { _toast.value = null }

    init {
        viewModelScope.launch {
            // 等启动恢复完成，避免把遗留的“生成中”当成正在跑的请求
            coordinator.recovered.filter { it }.first()
            // 存在未过期软删除：恢复剩余撤销机会，并保留原截止时间
            coordinator.lastRecovery.value?.let { recovery ->
                recovery.pendingUndo?.let { entity ->
                    if (recovery.undoRemainingMs > 0) armUndoTimer(entity, recovery.undoRemainingMs)
                }
            }
            val last = prefsLastSession()
            if (last != null && chatRepo.sessionById(last) != null) {
                openSessionInternal(last)
            }
            _draft.value = chatRepo.draftFor(_currentSessionId.value)
        }
        // 草稿写盘失败必须让用户看到，不能显示成已保存
        viewModelScope.launch {
            draftWriter.state.collect { state ->
                if (state?.phase == DraftSaveState.Phase.FAILED) {
                    _toast.value = state.message ?: "草稿保存失败"
                }
            }
        }
        // 存储类错误（写盘失败等）
        viewModelScope.launch {
            coordinator.storageErrors.collect { _toast.value = it }
        }
    }

    private suspend fun prefsLastSession(): String? = prefs.lastSessionId.first()

    // ---------- 会话切换 ----------

    fun openSession(id: String?) {
        viewModelScope.launch {
            // 切走前把当前草稿提交完，保证已输入内容不会因为切页面丢掉
            draftWriter.flush(_currentSessionId.value, _draft.value)
            openSessionInternal(id)
            _draft.value = chatRepo.draftFor(id)
        }
    }

    private suspend fun openSessionInternal(id: String?) {
        _currentSessionId.value = id
        prefs.setLastSessionId(id)
    }

    fun newChat() = openSession(null)

    // ---------- 发送 / 停止 / 重试 ----------

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty() || generating.value || !ready.value) return
        viewModelScope.launch {
            // 当前会话从数据库直读：`session` 这个 StateFlow 是异步更新的，
            // 刚切换过会话时它的值可能还是上一个，会把请求发给旧配置旧模型。
            val current = _currentSessionId.value?.let { chatRepo.sessionById(it) }
            // 确定本次使用的模型
            val configId: String
            val modelId: String
            val snapshot: String
            if (current != null && current.configId != null) {
                configId = current.configId
                modelId = current.modelId
                snapshot = current.modelNameSnapshot
            } else if (current != null && current.configId == null) {
                _toast.value = "原配置已删除，请通过顶部模型名换用其他模型"
                return@launch
            } else {
                val p = _pending.value ?: providers.value.firstOrNull()?.let {
                    PendingModel(it.id, it.selectedModelId, "${it.name} · ${it.selectedModelId}")
                }
                if (p == null) { _toast.value = "请先配置模型服务"; return@launch }
                configId = p.configId; modelId = p.modelId; snapshot = p.snapshot
            }

            // 先把草稿提交到磁盘，取得这次发送对应的确切版本
            val sourceSessionId = current?.id
            val committed = draftWriter.flush(sourceSessionId, text)
            val draftKey = chatRepo.draftKey(sourceSessionId)
            val draftVersion = committed?.version ?: chatRepo.draftVersion(sourceSessionId)
            val draftText = committed?.text ?: text

            val requestId = UUID.randomUUID().toString()
            // 一次事务提交：新建会话、用户消息、助手待回复记录、清理本次草稿
            val outcome = try {
                chatRepo.beginSend(
                    currentSessionId = sourceSessionId,
                    text = text,
                    configId = configId,
                    modelId = modelId,
                    modelNameSnapshot = snapshot,
                    requestId = requestId,
                    sentDraftKey = draftKey,
                    sentDraftVersion = draftVersion,
                    sentDraftText = draftText
                )
            } catch (e: Exception) {
                // 没落盘就不算发送成功：保留输入，明确提示
                _toast.value = "发送失败，消息未保存。请检查存储空间后重试"
                return@launch
            }

            if (sourceSessionId == null) openSessionInternal(outcome.session.id)
            if (_draft.value.trim() == text) _draft.value = ""

            val started = coordinator.start(
                GenerationRequest(
                    requestId = requestId,
                    sessionId = outcome.session.id,
                    assistantMessageId = outcome.assistantMessage.id,
                    assistantSeq = outcome.assistantMessage.seq,
                    configId = configId,
                    modelId = modelId,
                    initialContent = "",
                    replaceOnFirstDelta = false
                )
            )
            if (!started) {
                // 落盘但没能开始（恢复未完成或已有请求在跑）：明确收尾，不留下假的生成中
                chatRepo.commitAssistant(
                    outcome.assistantMessage.id,
                    "",
                    MessageStatus.INTERRUPTED,
                    ErrorKind.INTERRUPTED_CONN.name
                )
                _toast.value = "已有回复正在生成"
            }
        }
    }

    fun stop() = coordinator.stopByUser()

    /** 重试只针对最后一个尚未成功完成的回复；复用原用户消息，不发送新草稿 */
    fun retry(message: MessageEntity) {
        if (generating.value || !ready.value) return
        val sid = message.sessionId
        viewModelScope.launch {
            val msgs = chatRepo.messagesOf(sid)
            val lastAssistant = msgs.lastOrNull { it.role == Role.ASSISTANT }
            if (lastAssistant?.id != message.id) { _toast.value = "只能重试最后一条回复"; return@launch }
            val session = chatRepo.sessionById(sid) ?: return@launch
            val configId = session.configId
            if (configId == null) {
                _toast.value = "原配置已删除，请通过顶部模型名换用其他模型"
                return@launch
            }
            // 保留旧内容，状态置为生成中；新正文第一批提交后才替换
            chatRepo.updateMessage(message.id, message.content, MessageStatus.GENERATING, null)
            coordinator.start(
                GenerationRequest(
                    requestId = message.requestId ?: UUID.randomUUID().toString(),
                    sessionId = sid,
                    assistantMessageId = message.id,
                    assistantSeq = message.seq,
                    configId = configId,
                    modelId = session.modelId,
                    initialContent = message.content,
                    replaceOnFirstDelta = true
                )
            )
        }
    }

    // ---------- 会话管理 ----------

    fun renameSession(id: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { chatRepo.renameSession(id, title) }
    }

    fun deleteSession(id: String) {
        if (_pendingDelete.value != null) {
            _toast.value = "请先撤销或等待上次删除完成"
            return
        }
        viewModelScope.launch {
            // 删除正在生成的会话：同时取消请求
            if (coordinator.active.value?.sessionId == id) coordinator.stopByUser()
            val wasCurrent = _currentSessionId.value == id
            val entity = chatRepo.softDelete(id, UNDO_WINDOW_MS) ?: return@launch
            if (wasCurrent) openSession(null)   // 回到空聊天页
            _pendingDelete.value = entity
            armUndoTimer(entity, UNDO_WINDOW_MS)
        }
    }

    private fun armUndoTimer(entity: SessionEntity, windowMs: Long) {
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            delay(windowMs + 300)
            chatRepo.hardDelete(entity.id)
            if (_pendingDelete.value?.id == entity.id) _pendingDelete.value = null
        }
    }

    fun undoDelete() {
        val entity = _pendingDelete.value ?: return
        undoJob?.cancel()
        viewModelScope.launch {
            chatRepo.undoDelete(entity.id)
            _pendingDelete.value = null
            openSession(entity.id)   // 恢复被删会话（删除当前会话的场景）
        }
    }

    // ---------- 换模型续聊 ----------

    private val _switchPageVisible = MutableStateFlow(false)

    /** 换模型续聊页面是否展开（页面状态交给 ViewModel，切换期间不随重组丢失） */
    val switchPageVisible: StateFlow<Boolean> = _switchPageVisible

    private val _switching = MutableStateFlow(false)

    /** 创建副本进行中：按钮据此禁用，连点也不会产生第二个副本 */
    val switching: StateFlow<Boolean> = _switching

    fun openSwitchModel() {
        if (providers.value.isEmpty()) return
        // 每次展开都重新读一次可用模型，避免用到已经删除的配置
        _switchPageVisible.value = true
    }

    fun closeSwitchModel() {
        // 创建过程中不允许收起：否则结果回来时用户已经看不到提示
        if (_switching.value) return
        _switchPageVisible.value = false
    }

    fun modelTargets(): List<ModelTarget> = providers.value.flatMap { p ->
        // 用 modelsOf：模型列表 ∪ 当前选中的模型，避免换过的模型互相残留
        configRepo.modelsOf(p).map { ModelTarget(p.id, p.name, it) }
    }

    /**
     * 空会话直接选择模型；有内容的会话先创建独立副本再打开。
     * 副本会带上目标配置与目标模型，之后发送、重试都从副本读取，原会话一行不改。
     */
    fun switchModel(
        target: ModelTarget,
        newTitle: String,
        onResult: (success: Boolean, error: String?) -> Unit
    ) {
        if (_switching.value) return   // 连点只产生一次创建
        _switching.value = true
        viewModelScope.launch {
            try {
                // 与发送同理：从数据库直读当前会话，不用异步 StateFlow 的快照
                val current = _currentSessionId.value?.let { chatRepo.sessionById(it) }
                val provider = configRepo.providerById(target.configId)
                    ?: throw IllegalStateException("配置不存在")
                val snapshot = "${provider.name} · ${target.modelId}"

                if (current == null) {
                    // 空聊天页：只记住待选模型，不建会话
                    _pending.value = PendingModel(target.configId, target.modelId, snapshot)
                    _switchPageVisible.value = false
                    onResult(true, null)
                    return@launch
                }
                if (chatRepo.messageCount(current.id) == 0) {
                    // 还没有内容：就地换模型，不需要副本，也不需要标题
                    chatRepo.updateSessionModel(current.id, target.configId, target.modelId, snapshot)
                    _pending.value = PendingModel(target.configId, target.modelId, snapshot)
                    _switchPageVisible.value = false
                    onResult(true, null)
                    return@launch
                }

                val title = newTitle.trim()
                if (title.isEmpty()) {
                    onResult(false, "请输入新会话标题")
                    return@launch
                }
                val copy = chatRepo.copySessionWithModel(
                    sourceId = current.id,
                    newTitle = title,
                    targetConfigId = target.configId,
                    targetModelId = target.modelId,
                    targetSnapshot = snapshot
                )
                openSessionInternal(copy.id)
                _draft.value = chatRepo.draftFor(copy.id)
                // 副本自带目标模型，待选值已经用不上了
                _pending.value = null
                _switchPageVisible.value = false
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, "创建失败，请重试")
            } finally {
                _switching.value = false
            }
        }
    }

    /** 展示用错误文案（对应规格第 6 节的可操作提示） */
    fun errorText(kind: String?): Pair<String, String?> = when (kind) {
        ErrorKind.NO_NETWORK.name -> "网络不可用，请连接后重试" to "retry"
        ErrorKind.TIMEOUT.name -> "连接超时，请重试" to "retry"
        ErrorKind.INTERRUPTED_CONN.name -> "连接中断，请重试。" to "retry"
        ErrorKind.AUTH.name -> "API Key 无效，请检查配置" to "settings"
        ErrorKind.QUOTA.name -> "服务额度不足，请检查账户" to "settings"
        ErrorKind.RATE_LIMIT.name -> "请求过于频繁，请稍后重试" to "retry"
        ErrorKind.MODEL.name -> "模型不可用，请重新选择" to "model"
        ErrorKind.CONTEXT.name -> "对话过长，请新建会话或选择容量更大的模型" to null
        ErrorKind.UNAVAILABLE.name -> "服务暂时不可用，请重试" to "retry"
        else -> "请求失败，请重试" to "retry"
    }

    /** 截断回复的说明：正文可能不完整，不是失败也不是完整回答 */
    fun truncationText(): Pair<String, String?> =
        "回复被中断，内容可能不完整" to "retry"

    private companion object {
        const val UNDO_WINDOW_MS = 5000L
    }
}

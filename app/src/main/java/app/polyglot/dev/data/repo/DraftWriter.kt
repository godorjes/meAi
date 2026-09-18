package app.polyglot.dev.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 草稿落盘状态，界面用它给出“保存中／已保存／保存失败”的边界 */
data class DraftSaveState(
    val key: String,
    val phase: Phase,
    val message: String? = null
) {
    enum class Phase { SAVING, SAVED, FAILED }
}

/**
 * 草稿写入器。
 *
 * 输入不再等待“停止输入 400ms”才保存，而是每次输入都尽快排队落盘，
 * 并且由**唯一一条串行流水线**按 FIFO 顺序写库：写入顺序与输入顺序一致，
 * 旧内容不可能覆盖新内容。流水线跑在应用作用域上，界面销毁后仍会继续提交。
 *
 * 队列里堆积的多条输入会合并：真正写库时取该会话当前最新文本，
 * 与上次已提交内容相同时直接跳过，因此快速输入不会产生等量的磁盘写入。
 *
 * [flush] 用于发送前：它把指定文本排到队尾并等待提交完成，
 * 返回的 (text, version) 就是这次真正落盘的内容与版本，发送事务据此精确清理草稿。
 */
class DraftWriter(
    private val repo: ChatRepository,
    private val scope: CoroutineScope
) {

    data class Committed(val sessionId: String?, val text: String, val version: Long)

    private sealed interface Op {
        val sessionId: String?
        val text: String
    }

    private data class Write(override val sessionId: String?, override val text: String) : Op

    private data class Flush(
        override val sessionId: String?,
        override val text: String,
        val result: CompletableDeferred<Committed>
    ) : Op

    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val latest = HashMap<String, String>()
    private val committed = HashMap<String, Committed>()

    private val _state = MutableStateFlow<DraftSaveState?>(null)
    val state: StateFlow<DraftSaveState?> = _state

    @Volatile
    private var started = false

    private fun keyOf(sessionId: String?): String = repo.draftKey(sessionId)

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            scope.launch { consume() }
        }
    }

    /** 输入即调用；只记录最新文本并排队，不做任何等待 */
    fun submit(sessionId: String?, text: String) {
        ensureStarted()
        synchronized(latest) { latest[keyOf(sessionId)] = text }
        ops.trySend(Write(sessionId, text))
    }

    /**
     * 把 [text] 提交为指定会话的当前草稿，并等待它真正落盘。
     * 返回已提交内容；若期间发生写入失败，返回上一次成功提交的内容（可能为 null）。
     */
    suspend fun flush(sessionId: String?, text: String): Committed? {
        ensureStarted()
        synchronized(latest) { latest[keyOf(sessionId)] = text }
        val deferred = CompletableDeferred<Committed>()
        ops.trySend(Flush(sessionId, text, deferred))
        return deferred.await()
    }

    /** 该会话最近一次成功落盘的内容 */
    fun lastCommitted(sessionId: String?): Committed? =
        synchronized(committed) { committed[keyOf(sessionId)] }

    private suspend fun consume() {
        for (op in ops) {
            val key = keyOf(op.sessionId)
            val text = if (op is Flush) op.text else synchronized(latest) { latest[key] } ?: op.text
            val already = synchronized(committed) { committed[key]?.text }

            if (already != text) {
                _state.value = DraftSaveState(key, DraftSaveState.Phase.SAVING)
                try {
                    val version = repo.appendDraft(op.sessionId, text)
                    synchronized(committed) {
                        committed[key] = Committed(op.sessionId, text, version)
                    }
                    _state.value = DraftSaveState(key, DraftSaveState.Phase.SAVED)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 落盘失败必须让用户看到：不能显示成已保存
                    _state.value = DraftSaveState(
                        key, DraftSaveState.Phase.FAILED, "草稿保存失败，请检查存储空间后重试"
                    )
                }
            }

            if (op is Flush) {
                val result = synchronized(committed) { committed[key] }
                    ?: Committed(op.sessionId, text, 0L)
                op.result.complete(result)
            }
        }
    }
}

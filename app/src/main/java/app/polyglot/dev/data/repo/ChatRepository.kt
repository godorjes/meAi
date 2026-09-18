package app.polyglot.dev.data.repo

import androidx.room.withTransaction
import app.polyglot.dev.data.local.AppDatabase
import app.polyglot.dev.data.local.DraftEntity
import app.polyglot.dev.data.local.MessageEntity
import app.polyglot.dev.data.local.MessageStatus
import app.polyglot.dev.data.local.NEW_CHAT_DRAFT_ID
import app.polyglot.dev.data.local.Role
import app.polyglot.dev.data.local.SessionEntity
import app.polyglot.dev.data.net.ChatMessage
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** 一次发送提交完成后的落盘结果 */
data class SendOutcome(
    val session: SessionEntity,
    val userMessage: MessageEntity,
    val assistantMessage: MessageEntity,
    val draftCleared: Boolean
)

/** 启动恢复的结果 */
data class LaunchRecovery(
    val interruptedCount: Int,
    val pendingUndo: SessionEntity?,
    val undoRemainingMs: Long
)

class ChatRepository(private val db: AppDatabase) {

    private val sessionDao get() = db.sessionDao()
    private val messageDao get() = db.messageDao()
    private val draftDao get() = db.draftDao()

    val activeSessions: Flow<List<SessionEntity>> = sessionDao.observeActive()

    fun observeSession(id: String): Flow<SessionEntity?> = sessionDao.observeById(id)

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        messageDao.observeBySession(sessionId)

    suspend fun sessionById(id: String): SessionEntity? = sessionDao.byId(id)

    suspend fun messagesOf(sessionId: String): List<MessageEntity> =
        messageDao.listBySession(sessionId)

    suspend fun createSession(
        title: String,
        configId: String,
        modelId: String,
        modelNameSnapshot: String,
        sourceSessionId: String? = null,
        sourceTitle: String? = null
    ): SessionEntity {
        val now = System.currentTimeMillis()
        val entity = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            configId = configId,
            modelId = modelId,
            modelNameSnapshot = modelNameSnapshot,
            sourceSessionId = sourceSessionId,
            sourceTitle = sourceTitle,
            createdAt = now,
            updatedAt = now,
            deleteUntil = null
        )
        sessionDao.upsert(entity)
        return entity
    }

    suspend fun updateSessionModel(sessionId: String, configId: String?, modelId: String, snapshot: String) {
        sessionDao.updateModel(sessionId, configId, modelId, snapshot, System.currentTimeMillis())
    }

    /** 追加消息，返回新消息（seq 自动取最大值的下一个） */
    suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        status: String,
        requestId: String?
    ): MessageEntity {
        val seq = messageDao.maxSeq(sessionId) + 1
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            seq = seq,
            content = content,
            status = status,
            requestId = requestId,
            errorKind = null,
            createdAt = System.currentTimeMillis()
        )
        messageDao.upsert(entity)
        sessionDao.touch(sessionId, System.currentTimeMillis())
        return entity
    }

    // ---------- 发送：一次事务提交，落盘成功才发请求 ----------

    /**
     * 发送前的全部持久化放在同一个事务里：新建会话（如需）、默认标题、
     * 用户消息、助手待回复记录、清理本次发送对应的草稿。
     * 这个函数正常返回就代表数据已经落盘；抛异常则整体回滚，不会留下半套消息。
     */
    suspend fun beginSend(
        currentSessionId: String?,
        text: String,
        configId: String,
        modelId: String,
        modelNameSnapshot: String,
        requestId: String,
        sentDraftKey: String,
        sentDraftVersion: Long,
        sentDraftText: String
    ): SendOutcome = db.withTransaction {
        val now = System.currentTimeMillis()

        val existing = currentSessionId?.let { sessionDao.byId(it) }
        val session: SessionEntity = if (existing == null) {
            SessionEntity(
                id = UUID.randomUUID().toString(),
                title = defaultTitle(text),
                configId = configId,
                modelId = modelId,
                modelNameSnapshot = modelNameSnapshot,
                sourceSessionId = null,
                sourceTitle = null,
                createdAt = now,
                updatedAt = now,
                deleteUntil = null
            ).also { sessionDao.upsert(it) }
        } else {
            if (existing.title.isBlank()) {
                sessionDao.rename(existing.id, defaultTitle(text), now)
                existing.copy(title = defaultTitle(text), updatedAt = now)
            } else {
                existing
            }
        }

        val userSeq = messageDao.maxSeq(session.id) + 1
        val userMessage = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            role = Role.USER,
            seq = userSeq,
            content = text,
            status = MessageStatus.DONE,
            requestId = null,
            errorKind = null,
            createdAt = now
        )
        messageDao.upsert(userMessage)

        val assistantMessage = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            role = Role.ASSISTANT,
            seq = userSeq + 1,
            content = "",
            status = MessageStatus.GENERATING,
            requestId = requestId,
            errorKind = null,
            createdAt = now
        )
        messageDao.upsert(assistantMessage)

        // 只清理本次真正发送的那条草稿。发送期间的新输入版本更高，不会被删掉；
        // 其他会话和空聊天页的独立草稿也不受影响。
        val cleared = draftDao.clearIfUnchanged(sentDraftKey, sentDraftVersion, sentDraftText) > 0

        sessionDao.touch(session.id, now)

        SendOutcome(
            session = session,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            draftCleared = cleared
        )
    }

    /** 写入助手正文与状态；返回是否写入成功（磁盘失败会抛出，由调用方处理） */
    suspend fun commitAssistant(
        messageId: String,
        content: String,
        status: String,
        errorKind: String? = null
    ) {
        messageDao.updateContentStatus(messageId, content, status, errorKind)
    }

    suspend fun updateMessage(id: String, content: String, status: String, errorKind: String? = null) {
        messageDao.updateContentStatus(id, content, status, errorKind)
    }

    suspend fun updateMessageStatus(id: String, status: String) {
        messageDao.updateStatus(id, status)
    }

    suspend fun renameSession(id: String, title: String) {
        sessionDao.rename(id, title.trim(), System.currentTimeMillis())
    }

    suspend fun touchSession(id: String) = sessionDao.touch(id, System.currentTimeMillis())

    suspend fun messageCount(sessionId: String): Int = messageDao.countBySession(sessionId)

    /** 首条有效提问前 20 字符作为默认标题，去除换行 */
    fun defaultTitle(firstQuestion: String): String =
        firstQuestion.replace("\n", " ").trim().take(20)

    // ---------- 删除与撤销 ----------

    suspend fun pendingDeleted(): List<SessionEntity> = sessionDao.pendingDeleted()

    suspend fun softDelete(id: String, windowMs: Long): SessionEntity? {
        val entity = sessionDao.byId(id) ?: return null
        val until = System.currentTimeMillis() + windowMs
        sessionDao.setDeleteUntil(id, until)
        return entity.copy(deleteUntil = until)
    }

    suspend fun undoDelete(id: String) = sessionDao.setDeleteUntil(id, null)

    suspend fun hardDelete(id: String) {
        db.withTransaction {
            messageDao.deleteBySession(id)
            // 草稿必须跟会话一起删：切走会话时会把它的草稿落盘，
            // 留在库里会变成没有归属的孤儿记录
            draftDao.delete(id)
            sessionDao.hardDelete(id)
        }
    }

    suspend fun hardDeleteExpired() {
        val now = System.currentTimeMillis()
        sessionDao.pendingDeleted().filter { (it.deleteUntil ?: Long.MAX_VALUE) <= now }
            .forEach { hardDelete(it.id) }
    }

    // ---------- 换模型副本 ----------

    /** 原子复制：新会话复制当前文字记录及完成状态，原会话不变 */
    suspend fun copySessionWithModel(
        sourceId: String,
        newTitle: String,
        targetConfigId: String,
        targetModelId: String,
        targetSnapshot: String
    ): SessionEntity {
        return db.withTransaction {
            val source = sessionDao.byId(sourceId)
                ?: throw IllegalStateException("来源会话不存在")
            val now = System.currentTimeMillis()
            val copy = SessionEntity(
                id = UUID.randomUUID().toString(),
                title = newTitle.trim().ifBlank { source.title },
                configId = targetConfigId,
                modelId = targetModelId,
                modelNameSnapshot = targetSnapshot,
                sourceSessionId = source.id,
                sourceTitle = source.title,
                createdAt = now,
                updatedAt = now,
                deleteUntil = null
            )
            sessionDao.upsert(copy)
            val messages = messageDao.listBySession(sourceId)
            messages.forEach { m ->
                messageDao.upsert(
                    m.copy(id = UUID.randomUUID().toString(), sessionId = copy.id)
                )
            }
            copy
        }
    }

    /** 构造请求上下文：完成状态的助手回复才进入上下文；不含失败/停止/中断的部分回复 */
    fun buildContext(messages: List<MessageEntity>, beforeSeqExclusive: Int? = null): List<ChatMessage> =
        messages
            .filter { beforeSeqExclusive == null || it.seq < beforeSeqExclusive }
            .filter { it.role == Role.USER || (it.role == Role.ASSISTANT && it.status == MessageStatus.DONE) }
            .sortedBy { it.seq }
            .map { ChatMessage(it.role, it.content) }

    // ---------- 草稿 ----------

    /** 空聊天页使用的固定草稿键 */
    fun draftKey(sessionId: String?): String = sessionId ?: NEW_CHAT_DRAFT_ID

    suspend fun draftFor(sessionId: String?): String =
        draftDao.textOf(draftKey(sessionId)) ?: ""

    suspend fun draftVersion(sessionId: String?): Long =
        draftDao.versionOf(draftKey(sessionId)) ?: 0L

    /**
     * 顺序追加草稿并返回新的版本号。
     * 读当前版本、自增、写入都在同一个事务里完成，
     * 因此版本严格单调递增，任何一次写入都不会把内容回退到更旧的版本。
     *
     * 文本为空且本来就没有草稿行时直接跳过：仅仅切换会话就会触发一次提交，
     * 没有必要为每个看过的会话留下一行空草稿。已经存在的草稿被清空时仍会写入，
     * 这样"清空"这个动作本身也是有序且可判定的。
     */
    suspend fun appendDraft(sessionId: String?, text: String): Long = db.withTransaction {
        val id = draftKey(sessionId)
        val current = draftDao.versionOf(id)
        if (text.isEmpty() && current == null) return@withTransaction 0L
        val next = (current ?: 0L) + 1
        draftDao.upsert(DraftEntity(id, text, next, System.currentTimeMillis()))
        next
    }

    suspend fun moveDraftIfNeeded(fromSessionId: String?, toSessionId: String) {
        if (fromSessionId == null) {
            draftDao.byId(NEW_CHAT_DRAFT_ID)?.let { draftDao.delete(NEW_CHAT_DRAFT_ID) }
        }
    }

    // ---------- 启动恢复 ----------

    /**
     * 启动恢复：把遗留的“生成中”改为“已中断”（不自动重发），
     * 清理已过期的软删除，并把仍未过期的撤销机会连同剩余时间交回界面。
     */
    suspend fun recoverOnLaunch(): LaunchRecovery = db.withTransaction {
        val interrupted = messageDao.interruptAllGenerating()
        hardDeleteExpired()
        // 清掉历史遗留的孤儿草稿（会话已被永久删除，草稿却留在库里）
        draftDao.deleteOrphans(NEW_CHAT_DRAFT_ID)
        val pending = sessionDao.pendingDeleted().firstOrNull()
        val remaining = pending?.deleteUntil?.let { it - System.currentTimeMillis() } ?: 0L
        LaunchRecovery(
            interruptedCount = interrupted,
            pendingUndo = if (remaining > 0) pending else null,
            undoRemainingMs = if (remaining > 0) remaining else 0L
        )
    }
}

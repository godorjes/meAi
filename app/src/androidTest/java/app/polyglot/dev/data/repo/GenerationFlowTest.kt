package app.polyglot.dev.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.polyglot.dev.data.crypto.KeyCrypto
import app.polyglot.dev.data.local.AppDatabase
import app.polyglot.dev.data.local.DatabaseMigrations
import app.polyglot.dev.data.local.MessageStatus
import app.polyglot.dev.data.net.ChatMessage
import app.polyglot.dev.data.net.ChatStreamClient
import app.polyglot.dev.data.net.StreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成流程与"换模型续聊"的请求级验收。
 *
 * 这里用可控的假客户端驱动生成，断言的是**真正发出去的请求参数**
 * （服务地址 + 模型 ID），而不是界面上的名字有没有变。
 */
@RunWith(AndroidJUnit4::class)
class GenerationFlowTest {

    /** 记录每次请求的参数，并按脚本返回事件流 */
    private class FakeClient : ChatStreamClient {
        data class Request(val baseUrl: String, val apiKey: String, val modelId: String)

        val requests = mutableListOf<Request>()
        val modelsSeen = mutableListOf<List<ChatMessage>>()

        /** 每次 streamChat 的脚本；默认只回一段正文并正常结束 */
        var script: (Request) -> Flow<StreamEvent> = { req ->
            flow {
                emit(StreamEvent.Delta("来自 " + req.modelId + " 的回复"))
                emit(StreamEvent.Done)
            }
        }

        override suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> = emptyList()

        override suspend fun testChat(baseUrl: String, apiKey: String, modelId: String) = Unit

        override fun streamChat(
            baseUrl: String,
            apiKey: String,
            modelId: String,
            messages: List<ChatMessage>
        ): Flow<StreamEvent> {
            val request = Request(baseUrl, apiKey, modelId)
            synchronized(requests) {
                requests += request
                modelsSeen += messages
            }
            return script(request)
        }

        fun lastRequest(): Request? = synchronized(requests) { requests.lastOrNull() }
        fun count(): Int = synchronized(requests) { requests.size }
    }

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var configRepo: ConfigRepository
    private lateinit var client: FakeClient
    private lateinit var coordinator: GenerationCoordinator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dbName = "generation-flow-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
        repo = ChatRepository(db)
        client = FakeClient()
        configRepo = ConfigRepository(db, KeyCrypto(context), client)
        coordinator = GenerationCoordinator(
            repo = repo,
            configRepo = configRepo,
            client = client,
            networkAvailable = { true },
            scope = scope
        )
        // 生成任务跑在应用作用域上，测试里先让它认为启动恢复已完成
        runBlocking { coordinator.recoverOnLaunch() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(dbName)
    }

    private suspend fun provider(name: String, baseUrl: String, model: String): String {
        val result = configRepo.saveProvider(
            existingId = null,
            name = name,
            providerType = "custom",
            baseUrl = baseUrl,
            apiKey = "sk-" + name,
            modelIds = listOf(model),
            selectedModelId = model
        )
        return (result as SaveProviderResult.Saved).entity.id
    }

    /** 走一遍真实发送：事务落盘 + 启动生成 */
    private suspend fun sendIn(sessionId: String, text: String, configId: String, modelId: String) {
        val requestId = "req-" + text
        val outcome = repo.beginSend(
            currentSessionId = sessionId,
            text = text,
            configId = configId,
            modelId = modelId,
            modelNameSnapshot = "快照",
            requestId = requestId,
            sentDraftKey = repo.draftKey(sessionId),
            sentDraftVersion = 0L,
            sentDraftText = ""
        )
        coordinator.start(
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
        // 等这次生成跑完
        withTimeoutOrNull(5_000) {
            while (coordinator.active.value != null) delay(20)
        }
    }

    @Test
    fun 换模型副本发送时请求用的是目标服务与目标模型() = runBlocking {
        val configA = provider("A", "https://a.test/v1", "model-a")
        val configB = provider("B", "https://b.test/v1", "model-b")

        // 原会话用配置 A
        val first = repo.beginSend(
            currentSessionId = null, text = "第一问", configId = configA, modelId = "model-a",
            modelNameSnapshot = "A · model-a", requestId = "r1",
            sentDraftKey = repo.draftKey(null), sentDraftVersion = 0L, sentDraftText = ""
        )
        coordinator.start(
            GenerationRequest(
                requestId = "r1", sessionId = first.session.id,
                assistantMessageId = first.assistantMessage.id, assistantSeq = first.assistantMessage.seq,
                configId = configA, modelId = "model-a", initialContent = "", replaceOnFirstDelta = false
            )
        )
        withTimeoutOrNull(5_000) { while (coordinator.active.value != null) delay(20) }
        assertEquals("https://a.test/v1", client.lastRequest()?.baseUrl)
        assertEquals("model-a", client.lastRequest()?.modelId)
        val originalIdentity = client.modelsSeen.last().first()
        assertEquals("system", originalIdentity.role)
        assertTrue(originalIdentity.content.contains("model-a"))

        // 换模型：复制到配置 B 的 model-b
        val copy = repo.copySessionWithModel(
            sourceId = first.session.id,
            newTitle = "换成 B",
            targetConfigId = configB,
            targetModelId = "model-b",
            targetSnapshot = "B · model-b"
        )
        assertEquals(configB, copy.configId)
        assertEquals("model-b", copy.modelId)

        val beforeCopy = client.count()
        sendIn(copy.id, "第二问", copy.configId!!, copy.modelId)

        // 关键断言：这次请求发到了目标服务与目标模型，而不是原会话的 A
        assertEquals(1, client.count() - beforeCopy)
        assertEquals("https://b.test/v1", client.lastRequest()?.baseUrl)
        assertEquals("model-b", client.lastRequest()?.modelId)
        assertEquals("sk-B", client.lastRequest()?.apiKey)
        val copiedContext = client.modelsSeen.last()
        assertEquals(1, copiedContext.count { it.role == "system" })
        assertEquals("system", copiedContext.first().role)
        assertTrue(copiedContext.first().content.contains("model-b"))
        assertFalse(copiedContext.first().content.contains("model-a"))
        assertTrue(copiedContext.any { it.role == "user" && it.content == "第一问" })
        assertTrue(copiedContext.any { it.role == "assistant" && it.content == "来自 model-a 的回复" })

        // 原会话一行不改
        val source = repo.sessionById(first.session.id)!!
        assertEquals(configA, source.configId)
        assertEquals("model-a", source.modelId)
        assertEquals(2, repo.messageCount(first.session.id))
        assertTrue(repo.messagesOf(first.session.id).none { it.content == "第二问" })
    }

    @Test
    fun 重试使用的是本会话当前的模型() = runBlocking {
        val configA = provider("A", "https://a.test/v1", "model-a")
        val configB = provider("B", "https://b.test/v1", "model-b")
        val session = repo.createSession("会话", configA, "model-a", "A · model-a")
        repo.appendMessage(session.id, "user", "问题", MessageStatus.DONE, null)
        val assistant = repo.appendMessage(session.id, "assistant", "半截回复", MessageStatus.STOPPED, "r1")

        // 会话换成 B 之后再重试：请求必须走 B
        repo.updateSessionModel(session.id, configB, "model-b", "B · model-b")
        val currentModel = repo.sessionById(session.id)!!
        coordinator.start(
            GenerationRequest(
                requestId = "r2", sessionId = session.id, assistantMessageId = assistant.id,
                assistantSeq = assistant.seq, configId = currentModel.configId,
                modelId = currentModel.modelId, initialContent = assistant.content,
                replaceOnFirstDelta = true
            )
        )
        withTimeoutOrNull(5_000) { while (coordinator.active.value != null) delay(20) }

        assertEquals("https://b.test/v1", client.lastRequest()?.baseUrl)
        assertEquals("model-b", client.lastRequest()?.modelId)
        // 旧内容被新正文替换掉，不是拼接
        val retryContext = client.modelsSeen.last()
        assertEquals("system", retryContext.first().role)
        assertTrue(retryContext.first().content.contains("model-b"))
        assertFalse(retryContext.first().content.contains("model-a"))
        assertFalse(retryContext.any { it.content == "半截回复" })
        val after = repo.messagesOf(session.id).first { it.id == assistant.id }
        assertEquals(MessageStatus.DONE, after.status)
        assertEquals("来自 model-b 的回复", after.content)
    }

    @Test
    fun 同一时间只有一个请求在跑() = runBlocking {
        val configA = provider("A", "https://a.test/v1", "model-a")
        val gate = CompletableDeferred<Unit>()
        client.script = { req ->
            flow {
                gate.await()
                emit(StreamEvent.Delta("x"))
                emit(StreamEvent.Done)
            }
        }
        val outcome = repo.beginSend(
            currentSessionId = null, text = "问题", configId = configA, modelId = "model-a",
            modelNameSnapshot = "A", requestId = "r1",
            sentDraftKey = repo.draftKey(null), sentDraftVersion = 0L, sentDraftText = ""
        )
        val request = GenerationRequest(
            requestId = "r1", sessionId = outcome.session.id,
            assistantMessageId = outcome.assistantMessage.id, assistantSeq = outcome.assistantMessage.seq,
            configId = configA, modelId = "model-a", initialContent = "", replaceOnFirstDelta = false
        )
        assertTrue("第一次应能启动", coordinator.start(request))
        // 连点：后续几次都必须被拒绝，不能产生第二个请求
        repeat(4) { assertFalse("生成中不应接受新请求", coordinator.start(request)) }
        Thread.sleep(200)
        assertEquals(1, client.count())

        gate.complete(Unit)
        withTimeoutOrNull(5_000) { while (coordinator.active.value != null) delay(20) }
        // 结束后才能接受下一次
        assertTrue(coordinator.start(request))
        gate.complete(Unit)
        withTimeoutOrNull(5_000) { while (coordinator.active.value != null) delay(20) }
        assertEquals(2, client.count())
    }

    @Test
    fun 没有结束标记的流标记为截断而不是完成() = runBlocking {
        val configA = provider("A", "https://a.test/v1", "model-a")
        client.script = {
            flow {
                emit(StreamEvent.Delta("写了一半"))
                emit(StreamEvent.Truncated("服务未返回结束标记"))
            }
        }
        coordAppendAndRun(configA, "model-a")
        val message = repo.messagesOf(sessionIdOfLast()).last()
        assertEquals(MessageStatus.TRUNCATED, message.status)
        assertEquals("写了一半", message.content)
    }

    @Test
    fun 用户停止保留已收到内容并标记已停止() = runBlocking {
        val configA = provider("A", "https://a.test/v1", "model-a")
        val gate = CompletableDeferred<Unit>()
        client.script = {
            flow {
                emit(StreamEvent.Delta("已经收到的内容"))
                gate.await()   // 一直挂着，等外部取消
                emit(StreamEvent.Done)
            }
        }
        coordAppendAndRun(configA, "model-a", waitForFinish = false)
        // 等第一批正文落盘
        withTimeoutOrNull(5_000) {
            while (repo.messagesOf(sessionIdOfLast()).last().content.isEmpty()) delay(20)
        }
        coordinator.stopByUser()
        withTimeoutOrNull(5_000) { while (coordinator.active.value != null) delay(20) }

        val message = repo.messagesOf(sessionIdOfLast()).last()
        assertEquals(MessageStatus.STOPPED, message.status)
        assertEquals("已经收到的内容", message.content)
    }

    @Test
    fun 启动恢复完成前拒绝开始生成(): Unit = runBlocking {
        val configA = provider("A", "https://a.test/v1", "model-a")
        // 造一个还没跑启动恢复的协调器
        val fresh = GenerationCoordinator(repo, configRepo, client, { true }, scope)
        val outcome = repo.beginSend(
            currentSessionId = null, text = "问题", configId = configA, modelId = "model-a",
            modelNameSnapshot = "A", requestId = "r1",
            sentDraftKey = repo.draftKey(null), sentDraftVersion = 0L, sentDraftText = ""
        )
        val request = GenerationRequest(
            requestId = "r1", sessionId = outcome.session.id,
            assistantMessageId = outcome.assistantMessage.id, assistantSeq = outcome.assistantMessage.seq,
            configId = configA, modelId = "model-a", initialContent = "", replaceOnFirstDelta = false
        )
        assertFalse("恢复未完成时不得开始生成", fresh.start(request))
        assertEquals(0, client.count())
        fresh.recoverOnLaunch()
        assertTrue(fresh.start(request))
        withTimeoutOrNull(5_000) { while (coordinator.active.value != null) delay(20) }
    }

    // ----- 小工具 -----

    private var lastSessionId: String? = null

    private suspend fun sessionIdOfLast(): String = lastSessionId!!

    private suspend fun coordAppendAndRun(
        configId: String,
        modelId: String,
        waitForFinish: Boolean = true
    ) {
        val outcome = repo.beginSend(
            currentSessionId = null, text = "问题", configId = configId, modelId = modelId,
            modelNameSnapshot = "A", requestId = "r1",
            sentDraftKey = repo.draftKey(null), sentDraftVersion = 0L, sentDraftText = ""
        )
        lastSessionId = outcome.session.id
        coordinator.start(
            GenerationRequest(
                requestId = "r1", sessionId = outcome.session.id,
                assistantMessageId = outcome.assistantMessage.id, assistantSeq = outcome.assistantMessage.seq,
                configId = configId, modelId = modelId, initialContent = "", replaceOnFirstDelta = false
            )
        )
        if (waitForFinish) {
            withTimeoutOrNull(5_000) { while (coordinator.active.value != null) delay(20) }
        }
    }
}

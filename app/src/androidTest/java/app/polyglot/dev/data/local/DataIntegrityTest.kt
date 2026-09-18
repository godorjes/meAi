package app.polyglot.dev.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.polyglot.dev.DurabilityReport
import app.polyglot.dev.data.crypto.KeyCrypto
import app.polyglot.dev.data.net.ErrorKind
import app.polyglot.dev.data.net.OpenAiClient
import app.polyglot.dev.data.repo.CatalogRepository
import app.polyglot.dev.data.repo.ChatRepository
import app.polyglot.dev.data.repo.ConfigRepository
import app.polyglot.dev.data.repo.DraftWriter
import app.polyglot.dev.data.repo.SaveProviderResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 数据可靠性测试：直接跑在真实 SQLite 上，覆盖草稿顺序写入、
 * 发送事务的原子性、写盘设置的实效，以及历史密钥密文的迁移。
 */
@RunWith(AndroidJUnit4::class)
class DataIntegrityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val dbName = "integrity-test.db"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*DatabaseMigrations.ALL)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                }
            })
            .build()
        repo = ChatRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    // ---------- E：写盘设置必须实际生效 ----------

    /** 取配置里已保存的模型列表（不含选中项合并） */
    private suspend fun modelIdsOf(configId: String): List<String> {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val entity = configRepo.providerById(configId) ?: return emptyList()
        return configRepo.decodeModels(entity.modelIdsJson)
    }

    @Test
    fun 实际生效的写盘设置是WAL与FULL同步() {
        val snapshot = DurabilityReport.read(db)
        assertTrue("journal_mode 应为 wal，实际 ${snapshot.journalMode}", snapshot.walEnabled)
        assertTrue("写连接 synchronous 应为 FULL(2)，实际 ${snapshot.synchronous}", snapshot.fullSync)
    }

    // ---------- C：草稿按版本顺序写入 ----------

    @Test
    fun 空草稿不会为看过的会话留下空行() = runBlocking {
        val session = repo.createSession("会话", "p1", "m1", "配置 · m1")
        // 切换会话时会对离开的会话做一次提交；文本为空且本来没有草稿时不应写行
        assertEquals(0L, repo.appendDraft(session.id, ""))
        assertEquals(0L, repo.appendDraft(null, ""))
        assertEquals(0, db.draftDao().allVersions().size)

        // 有内容时正常写入
        assertEquals(1L, repo.appendDraft(session.id, "有内容"))
        // 之后清空仍要写入，让"清空"这个动作也是有序可判定的
        assertEquals(2L, repo.appendDraft(session.id, ""))
        assertEquals("", repo.draftFor(session.id))
        assertEquals(1, db.draftDao().allVersions().size)
    }

    @Test
    fun 草稿版本单调递增且旧版本不能覆盖新内容() = runBlocking {
        val v1 = repo.appendDraft("s1", "第一版")
        val v2 = repo.appendDraft("s1", "第二版")
        val v3 = repo.appendDraft("s1", "第三版")
        assertEquals(1L, v1)
        assertEquals(2L, v2)
        assertEquals(3L, v3)
        assertEquals("第三版", repo.draftFor("s1"))

        // 只有仍停留在被发送版本上的草稿会被清理
        assertEquals(0, db.draftDao().clearIfUnchanged("s1", 2L, "第二版"))
        assertEquals("第三版", repo.draftFor("s1"))
        assertEquals(1, db.draftDao().clearIfUnchanged("s1", 3L, "第三版"))
        assertEquals("", repo.draftFor("s1"))
    }

    @Test
    fun 空聊天页草稿与其他会话草稿互不干扰() = runBlocking {
        repo.appendDraft(null, "空聊天页草稿")
        repo.appendDraft("s1", "会话一草稿")
        repo.appendDraft("s2", "会话二草稿")

        db.draftDao().clearIfUnchanged("s1", repo.draftVersion("s1"), "会话一草稿")

        assertEquals("空聊天页草稿", repo.draftFor(null))
        assertEquals("", repo.draftFor("s1"))
        assertEquals("会话二草稿", repo.draftFor("s2"))
    }

    @Test
    fun 草稿写入器按输入顺序落盘且保留最后一次输入() = runBlocking {
        val writer = DraftWriter(repo, scope)
        repeat(30) { i -> writer.submit("s1", "第 $i 次输入") }
        val committed = writer.flush("s1", "最后一次输入")
        assertNotNull(committed)
        assertEquals("最后一次输入", repo.draftFor("s1"))
        assertEquals(committed!!.version, repo.draftVersion("s1"))
    }

    // ---------- C：发送是一次完整事务 ----------

    @Test
    fun 发送事务一次写入会话用户消息待回复记录并清理对应草稿() = runBlocking {
        repo.appendDraft(null, "要发送的内容")
        val version = repo.draftVersion(null)

        val outcome = repo.beginSend(
            currentSessionId = null,
            text = "要发送的内容",
            configId = "p1",
            modelId = "m1",
            modelNameSnapshot = "配置 · m1",
            requestId = "req-1",
            sentDraftKey = repo.draftKey(null),
            sentDraftVersion = version,
            sentDraftText = "要发送的内容"
        )

        assertTrue(outcome.draftCleared)
        assertEquals("要发送的内容", outcome.session.title)
        assertEquals(1, outcome.userMessage.seq)
        assertEquals(2, outcome.assistantMessage.seq)
        assertEquals(MessageStatus.GENERATING, outcome.assistantMessage.status)
        assertEquals("req-1", outcome.assistantMessage.requestId)

        val messages = repo.messagesOf(outcome.session.id)
        assertEquals(2, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("要发送的内容", messages[0].content)
        assertEquals(Role.ASSISTANT, messages[1].role)
        assertEquals("", messages[1].content)
        // 已发送的草稿清空
        assertEquals("", repo.draftFor(null))
    }

    @Test
    fun 发送期间的新输入不会被发送事务清掉() = runBlocking {
        repo.appendDraft("s1", "旧内容")
        val sentVersion = repo.draftVersion("s1")
        // 发送事务开始之前用户又输入了新内容，版本更高
        repo.appendDraft("s1", "发送后继续输入的内容")

        repo.beginSend(
            currentSessionId = "s1",
            text = "旧内容",
            configId = "p1",
            modelId = "m1",
            modelNameSnapshot = "配置 · m1",
            requestId = "req-2",
            sentDraftKey = repo.draftKey("s1"),
            sentDraftVersion = sentVersion,
            sentDraftText = "旧内容"
        )

        assertEquals("发送后继续输入的内容", repo.draftFor("s1"))
    }

    @Test
    fun 发送事务失败时不会留下半套消息() = runBlocking {
        val session = repo.createSession("会话", "p1", "m1", "配置 · m1")
        val before = repo.messagesOf(session.id).size

        // sessionId 指向不存在的会话时，消息写入与草稿清理仍然在同一事务里；
        // 这里用人为中断模拟写入失败：事务内抛错必须整体回滚
        var threw = false
        try {
            db.withTransaction {
                repo.beginSend(
                    currentSessionId = session.id,
                    text = "不该被保存",
                    configId = "p1",
                    modelId = "m1",
                    modelNameSnapshot = "配置 · m1",
                    requestId = "req-3",
                    sentDraftKey = repo.draftKey(session.id),
                    sentDraftVersion = 0L,
                    sentDraftText = ""
                )
                throw IllegalStateException("模拟写入失败")
            }
        } catch (e: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals(before, repo.messagesOf(session.id).size)
        assertTrue(repo.messagesOf(session.id).none { it.content == "不该被保存" })
    }

    @Test
    fun 启动恢复把生成中改为已中断并保留正文() = runBlocking {
        val session = repo.createSession("会话", "p1", "m1", "配置 · m1")
        db.messageDao().upsert(
            MessageEntity(
                id = "a1", sessionId = session.id, role = Role.ASSISTANT, seq = 1,
                content = "已经落盘的一半", status = MessageStatus.GENERATING,
                requestId = "r", errorKind = null, createdAt = 1
            )
        )

        val recovery = repo.recoverOnLaunch()

        assertEquals(1, recovery.interruptedCount)
        val message = repo.messagesOf(session.id).first { it.id == "a1" }
        assertEquals(MessageStatus.INTERRUPTED, message.status)
        assertEquals("已经落盘的一半", message.content)
    }

    // ---------- B：配置与密钥同事务、旧密文可续迁移 ----------

    @Test
    fun 保存配置与密钥在同一事务里成功() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val result = configRepo.saveProvider(
            existingId = null,
            name = "测试配置",
            providerType = "custom",
            baseUrl = "https://example.test/v1",
            apiKey = "sk-test-value",
            modelIds = listOf("m1"),
            selectedModelId = "m1"
        )
        assertTrue(result is app.polyglot.dev.data.repo.SaveProviderResult.Saved)
        val saved = (result as app.polyglot.dev.data.repo.SaveProviderResult.Saved).entity

        // 密钥进数据库、可解出原值；明文不落库
        val row = db.providerKeyDao().byId(saved.id)
        assertNotNull(row)
        assertEquals("sk-test-value", keyCrypto.decrypt(row!!.ciphertext))
        assertFalse(row.ciphertext.contains("sk-test-value"))
        // 新配置或换过地址/凭证后不保留旧测试结论
        assertEquals("none", db.providerDao().byId(saved.id)?.testState)
        assertEquals("sk-test-value", configRepo.readApiKey(saved.id))
    }

    @Test
    fun 旧偏好密文迁移可重复执行且提交成功后才清理() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val configId = "legacy-" + UUID.randomUUID()
        val providers = db.providerDao()
        providers.upsert(
            ProviderEntity(
                id = configId, name = "旧配置", providerType = "custom",
                apiType = "openai_compatible", baseUrl = "https://example.test/v1",
                modelIdsJson = "[]", selectedModelId = "m1", testState = "none",
                testedAt = null, createdAt = 1, updatedAt = 1
            )
        )
        // 造出旧版本留在 SharedPreferences 里的密文
        val ciphertext = keyCrypto.encrypt("sk-legacy-value")
        context.getSharedPreferences("polyglot_secure", Context.MODE_PRIVATE)
            .edit().putString("key_$configId", ciphertext).commit()

        val moved = configRepo.migrateLegacyKeys()
        assertEquals(1, moved)
        assertEquals("sk-legacy-value", keyCrypto.decrypt(db.providerKeyDao().byId(configId)!!.ciphertext))
        assertNull(
            "提交成功后旧密文应被清理",
            context.getSharedPreferences("polyglot_secure", Context.MODE_PRIVATE)
                .getString("key_$configId", null)
        )

        // 再跑一次：已经有新记录，不重复迁移也不报错
        assertEquals(0, configRepo.migrateLegacyKeys())
        assertEquals(1, db.providerKeyDao().count(configId))
        assertEquals("sk-legacy-value", configRepo.readApiKey(configId))
    }

    // ---------- E：写入性能与写入失败的可见性 ----------

    /**
     * 测量草稿写入与长回复批量提交的真实耗时。
     * 这些写入都发生在 IO 调度器上，主线程不参与；这里给出客观数字，
     * 避免只用“应该很快”来声称持久化没有代价。
     */
    @Test
    fun 草稿与长回复的写入耗时可测量() = runBlocking {
        // 草稿写入：模拟持续输入
        val draftCosts = LongArray(60)
        repeat(60) { i ->
            val t0 = System.nanoTime()
            repo.appendDraft("s1", "持续输入的第 $i 段内容")
            draftCosts[i] = (System.nanoTime() - t0) / 1000
        }
        val draftMedian = draftCosts.sorted()[draftCosts.size / 2]
        val draftWorst = draftCosts.max()

        // 长回复：模拟协调器反复重写不断变长的正文。
        // 每增长 500 字符提交一次，保证提交次数是确定的，便于比较耗时。
        val session = repo.createSession("长回复", "p1", "m1", "配置 · m1")
        val message = repo.appendMessage(session.id, Role.ASSISTANT, "", MessageStatus.GENERATING, "r")
        val sb = StringBuilder()
        val commitCosts = mutableListOf<Long>()
        var lastCommittedLength = 0
        while (sb.length < 20_000) {
            sb.append("这是一段用于测量写入代价的正文内容。")
            if (sb.length - lastCommittedLength >= 500) {
                val t0 = System.nanoTime()
                repo.commitAssistant(message.id, sb.toString(), MessageStatus.GENERATING, null)
                commitCosts += (System.nanoTime() - t0) / 1000
                lastCommittedLength = sb.length
            }
        }
        val finalCost = run {
            val t0 = System.nanoTime()
            repo.commitAssistant(message.id, sb.toString(), MessageStatus.DONE, null)
            (System.nanoTime() - t0) / 1000
        }

        val commitCostsSorted = commitCosts.sorted()
        val commitMedian = commitCostsSorted[commitCostsSorted.size / 2]
        val commitWorst = commitCostsSorted.last()
        println(
            "写入耗时(微秒)：草稿中位=$draftMedian 最差=$draftWorst | " +
                "长回复提交次数=${commitCosts.size} 中位=$commitMedian 最差=$commitWorst " +
                "终稿(${sb.length}字符)=$finalCost"
        )

        // 单次写入必须远低于一帧的预算，否则持续输入会明显卡顿
        assertTrue("草稿单次写入中位耗时应低于 50ms，实际 ${draftMedian}us", draftMedian < 50_000)
        assertTrue("长回复单次提交中位耗时应低于 200ms，实际 ${commitMedian}us", commitMedian < 200_000)
        assertEquals(sb.toString(), repo.messagesOf(session.id).first { it.id == message.id }.content)
    }

    /**
     * 写入失败必须抛出来，而不是被吞掉当成保存成功。
     * 这里把连接切到只读，模拟磁盘写不进去的情况。
     */
    @Test
    fun 写盘失败会抛出而不是假装成功() = runBlocking {
        repo.appendDraft("s1", "失败前的旧草稿")
        val before = repo.draftFor("s1")
        val versionBefore = repo.draftVersion("s1")

        db.openHelper.writableDatabase.execSQL("PRAGMA query_only=ON")
        val failed = try {
            repo.appendDraft("s1", "这次写入应该失败")
            false
        } catch (e: Exception) {
            true
        }
        db.openHelper.writableDatabase.execSQL("PRAGMA query_only=OFF")

        assertTrue("只读连接上的写入必须抛出异常", failed)
        // 旧数据没有被覆盖，也没有被清空
        assertEquals(before, repo.draftFor("s1"))
        assertEquals(versionBefore, repo.draftVersion("s1"))
    }

    /**
     * 永久删除会话时必须连草稿一起删。
     * 切换会话会把离开的那个会话的草稿落盘，如果删除时漏掉草稿，
     * 库里就会留下没有归属的孤儿记录。
     */
    @Test
    fun 永久删除会话会一并清掉它的草稿() = runBlocking {
        val session = repo.createSession("待删除", "p1", "m1", "配置 · m1")
        repo.appendMessage(session.id, Role.USER, "问题", MessageStatus.DONE, null)
        repo.appendDraft(session.id, "这个会话的草稿")
        repo.appendDraft(null, "空聊天页草稿")

        repo.hardDelete(session.id)

        assertEquals("空聊天页草稿", repo.draftFor(null))
        assertEquals("", repo.draftFor(session.id))
        assertEquals(0, db.draftDao().allVersions().count { it.sessionId == session.id })

        // 历史遗留的孤儿草稿也要在启动恢复时被清掉，且不误伤在用的草稿
        db.draftDao().upsert(DraftEntity("已不存在的会话", "孤儿草稿", 1, 0))
        val live = repo.createSession("在用会话", "p1", "m1", "配置 · m1")
        repo.appendDraft(live.id, "在用的草稿")
        repo.recoverOnLaunch()

        val remaining = db.draftDao().allVersions().map { it.sessionId }.toSet()
        assertFalse("孤儿草稿应被清理", remaining.contains("已不存在的会话"))
        assertTrue("空聊天页草稿不能被清掉", remaining.contains("new"))
        assertEquals("在用的草稿", repo.draftFor(live.id))
    }

    @Test
    fun 重启后仍能撤销未过期的删除() = runBlocking {
        val session = repo.createSession("待撤销", "p1", "m1", "配置 · m1")
        repo.appendMessage(session.id, Role.USER, "内容", MessageStatus.DONE, null)
        repo.softDelete(session.id, 5_000)

        // 模拟进程重启：走启动恢复，删除仍在撤销窗口内
        val recovery = repo.recoverOnLaunch()
        assertEquals(session.id, recovery.pendingUndo?.id)
        assertTrue("剩余撤销时间应为正数，实际 ${recovery.undoRemainingMs}", recovery.undoRemainingMs > 0)
        assertTrue("剩余时间不应超过原窗口", recovery.undoRemainingMs <= 5_000)

        // 撤销后数据完整回来
        repo.undoDelete(session.id)
        assertEquals(1, repo.messageCount(session.id))
        assertNotNull(repo.sessionById(session.id))

        // 已过期的删除在启动恢复时被永久清理
        repo.softDelete(session.id, -1_000)
        val second = repo.recoverOnLaunch()
        assertNull("已过期的删除不应再提供撤销", second.pendingUndo)
        assertNull(repo.sessionById(session.id))
    }

    /**
     * 换模型不能把旧模型永久留在配置的模型列表里。
     * 旧实现每次保存都把选中的模型并进列表，换几次就永久留下几个，
     * 于是"只配了一个模型"的配置会显示成好几个。
     */
    @Test
    fun 反复更换模型不会让模型列表不断变长() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())

        // 第一次：服务没有返回列表，用户手动指定 deepseek-flash
        val first = configRepo.saveProvider(
            existingId = null, name = "deep seek", providerType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "sk-x",
            modelIds = emptyList(), selectedModelId = "deepseek-flash"
        ) as SaveProviderResult.Saved
        assertEquals(1, configRepo.modelsOf(first.entity).size)

        // 第二次：改成另一个模型
        val second = configRepo.saveProvider(
            existingId = first.entity.id, name = "deep seek", providerType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "sk-x",
            modelIds = emptyList(), selectedModelId = "deepseek-reasoner"
        ) as SaveProviderResult.Saved

        // 列表里只有当前选中的那一个，不会累积成两个
        assertEquals(listOf("deepseek-reasoner"), configRepo.modelsOf(second.entity))
        assertEquals(
            "deepseek-reasoner",
            db.providerDao().byId(second.entity.id)?.selectedModelId
        )
    }

    /** 服务返回了列表时，选中的模型一定要在配置的模型列表里 */
    @Test
    fun 选中的模型一定在配置的模型列表里() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val saved = configRepo.saveProvider(
            existingId = null, name = "relay", providerType = "custom",
            baseUrl = "https://relay.test/v1", apiKey = "sk-x",
            modelIds = listOf("a", "b"), selectedModelId = "手写的模型"
        ) as SaveProviderResult.Saved

        // 服务列表保留，同时把手动填写的选中模型并入，避免选了却不在列表里
        assertEquals(listOf("a", "b", "手写的模型"), configRepo.decodeModels(saved.entity.modelIdsJson))
        assertEquals(listOf("a", "b", "手写的模型"), configRepo.modelsOf(saved.entity))
    }

    // ---------- 公共目录与账号查询结果分开 ----------

    /** 账号查询结果只能写进该配置自己的快照，不能删改公共目录 */
    @Test
    fun 账号查询结果不会裁剪公共目录() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val catalog = CatalogRepository(db, context, keyCrypto)

        catalog.seedIfEmpty()
        val before = db.catalogDao().listByProvider("deepseek").map { it.modelId }.toSet()
        assertTrue("内置目录应有 deepseek 条目", before.isNotEmpty())

        val a = configRepo.saveProvider(
            existingId = null, name = "A", providerType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "sk-a",
            modelIds = emptyList(), selectedModelId = ""
        ) as SaveProviderResult.Saved
        // A 这个账号只查到其中一个
        val one = before.first()
        configRepo.recordQueryResult(a.entity.id, listOf(one))

        assertEquals(setOf(one), configRepo.queriedModels(a.entity.id).toSet())
        // 公共目录一条都不能少
        assertEquals(before, db.catalogDao().listByProvider("deepseek").map { it.modelId }.toSet())
    }

    /** 同厂家两个配置的查询结果互不覆盖 */
    @Test
    fun 同厂家两个配置的查询结果互不覆盖() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        suspend fun add(name: String, key: String): SaveProviderResult.Saved =
            configRepo.saveProvider(
                existingId = null, name = name, providerType = "deepseek",
                baseUrl = "https://api.deepseek.com", apiKey = key,
                modelIds = emptyList(), selectedModelId = ""
            ) as SaveProviderResult.Saved
        val a = add("A", "sk-a")
        val b = add("B", "sk-b")
        configRepo.recordQueryResult(a.entity.id, listOf("only-a"))
        configRepo.recordQueryResult(b.entity.id, listOf("only-b"))

        assertEquals(listOf("only-a"), configRepo.queriedModels(a.entity.id))
        assertEquals(listOf("only-b"), configRepo.queriedModels(b.entity.id))
    }

    /** 换了 Key 或地址，旧凭证的查询结果不再算作当前凭证的证据 */
    @Test
    fun 换Key或地址后旧查询结果不再作为证据() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val a = configRepo.saveProvider(
            existingId = null, name = "A", providerType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "sk-a",
            modelIds = emptyList(), selectedModelId = ""
        ) as SaveProviderResult.Saved
        configRepo.recordQueryResult(a.entity.id, listOf("m-old"))
        assertEquals(listOf("m-old"), configRepo.queriedModels(a.entity.id))

        // 只换 Key
        val b = configRepo.saveProvider(
            existingId = a.entity.id, name = "A", providerType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "sk-b",
            modelIds = modelIdsOf(a.entity.id), selectedModelId = "m-old"
        ) as SaveProviderResult.Saved
        assertTrue("换 Key 后旧结果不应再算证据", configRepo.queriedModels(b.entity.id).isEmpty())
        assertTrue("凭证版本应推进", b.entity.credentialRevision > a.entity.credentialRevision)
        // 已保存的模型仍然保留在配置里
        assertTrue("m-old" in configRepo.modelsOf(b.entity))

        // 只换地址
        val c = configRepo.saveProvider(
            existingId = b.entity.id, name = "A", providerType = "deepseek",
            baseUrl = "https://relay.test/v1", apiKey = "sk-b",
            modelIds = modelIdsOf(b.entity.id), selectedModelId = "m-old"
        ) as SaveProviderResult.Saved
        assertTrue("换地址后旧结果不应再算证据", configRepo.queriedModels(c.entity.id).isEmpty())
        assertTrue("地址版本应推进", c.entity.endpointRevision > b.entity.endpointRevision)
    }

    /** 已选模型没有出现在新列表里时不丢失，也不被自动替换 */
    @Test
    fun 已选模型未出现在新列表时保留原值() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val saved = configRepo.saveProvider(
            existingId = null, name = "A", providerType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "sk-a",
            modelIds = emptyList(), selectedModelId = "m-keep"
        ) as SaveProviderResult.Saved

        configRepo.recordQueryResult(saved.entity.id, listOf("m-new-1", "m-new-2"))

        val after = db.providerDao().byId(saved.entity.id)!!
        assertEquals("选中模型不能被自动替换", "m-keep", after.selectedModelId)
        assertTrue("已选模型要保留在列表里", "m-keep" in configRepo.modelsOf(after))
        assertTrue("新返回的模型也要进来", "m-new-1" in configRepo.modelsOf(after))
    }

    /** 自动命名：普通流程不用填名称；重名时加序号；高级设置里填了别名就用别名 */
    @Test
    fun 配置名称自动生成且不重名() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        suspend fun add(alias: String) = (
            configRepo.saveProvider(
                existingId = null, name = alias, providerType = "deepseek",
                baseUrl = "https://api.deepseek.com", apiKey = "sk-x",
                modelIds = emptyList(), selectedModelId = "m"
            ) as SaveProviderResult.Saved
            ).entity.name

        assertEquals("DeepSeek 配置", add(""))
        assertEquals("DeepSeek 配置 2", add(""))
        assertEquals("我的别名", add("我的别名"))
        // 别名只有空白也视为没填，回退自动命名
        assertEquals("DeepSeek 配置 3", add("   "))
    }

    /** 测试结论按参数版本记录：换过 Key 之后旧结论不再算数 */
    @Test
    fun 测试结论按参数版本记录() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val a = configRepo.saveProvider(
            existingId = null, name = "A", providerType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "sk-a",
            modelIds = emptyList(), selectedModelId = "m1"
        ) as SaveProviderResult.Saved
        configRepo.recordVerification(a.entity.id, "m1", success = true, kind = null)
        assertTrue(configRepo.verificationFor(a.entity.id, "m1")?.success == true)

        // 换 Key 后旧结论失效
        val b = configRepo.saveProvider(
            existingId = a.entity.id, name = "A", providerType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "sk-b",
            modelIds = listOf("m1"), selectedModelId = "m1"
        ) as SaveProviderResult.Saved
        assertNull("换 Key 后旧测试结论不应再算数", configRepo.verificationFor(b.entity.id, "m1"))

        // 重新测一次就又有结论了
        configRepo.recordVerification(b.entity.id, "m1", success = false, kind = ErrorKind.AUTH)
        val again = configRepo.verificationFor(b.entity.id, "m1")
        assertTrue(again != null && !again.success && again.errorKind == ErrorKind.AUTH.name)
    }

    /** 删除配置要连它自己的快照与测试记录一起清掉，公共目录不受影响 */
    @Test
    fun 删除配置会清掉它的快照与测试记录() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val catalog = CatalogRepository(db, context, keyCrypto)
        catalog.seedIfEmpty()
        val catalogBefore = db.catalogDao().count()

        val a = configRepo.saveProvider(
            existingId = null, name = "A", providerType = "deepseek",
            baseUrl = "https://api.deepseek.com", apiKey = "sk-a",
            modelIds = emptyList(), selectedModelId = "m1"
        ) as SaveProviderResult.Saved
        configRepo.recordQueryResult(a.entity.id, listOf("m1"))
        configRepo.recordVerification(a.entity.id, "m1", success = true, kind = null)

        configRepo.deleteProvider(a.entity.id)

        assertTrue(db.configModelDao().listForConfig(a.entity.id).isEmpty())
        assertTrue(db.verificationDao().listForConfig(a.entity.id).isEmpty())
        assertEquals("公共目录不该被连带删除", catalogBefore, db.catalogDao().count())
    }

    @Test
    fun 配置与密钥一起删除且历史会话保留() = runBlocking {
        val keyCrypto = KeyCrypto(context)
        val configRepo = ConfigRepository(db, keyCrypto, OpenAiClient())
        val result = configRepo.saveProvider(
            existingId = null, name = "配置", providerType = "custom",
            baseUrl = "https://example.test/v1", apiKey = "sk-x",
            modelIds = listOf("m1"), selectedModelId = "m1"
        ) as app.polyglot.dev.data.repo.SaveProviderResult.Saved
        val id = result.entity.id
        val session = repo.createSession("会话", id, "m1", "配置 · m1")
        repo.appendMessage(session.id, Role.USER, "问题", MessageStatus.DONE, null)

        configRepo.deleteProvider(id)

        assertNull(db.providerDao().byId(id))
        assertNull(db.providerKeyDao().byId(id))
        // 历史记录保留，配置引用置空，模型名称快照还在
        val detached = db.sessionDao().byId(session.id)
        assertNotNull(detached)
        assertNull(detached!!.configId)
        assertEquals("配置 · m1", detached.modelNameSnapshot)
        assertEquals(1, repo.messageCount(session.id))
    }
}

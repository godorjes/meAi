package app.polyglot.dev.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.polyglot.dev.data.repo.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 升级迁移测试：任何一次升级都不允许丢数据，也不允许把库重建为空。
 *
 * v2 的结构取自 Room 导出的 2.json，并且已和真机 v2 库的 identity_hash 比对一致，
 * 因此这里创建出来的“旧库”就是用户真实持有的结构，不是猜测。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(V1_DB)
        context.deleteDatabase(FRESH_DB)
    }

    @Test
    fun 从v2升级到最新_会话消息草稿配置全部逐项保留() {
        val name = "mig23.db"
        helper.createDatabase(name, 2).use { db ->
            db.execSQL(
                """INSERT INTO providers (id,name,providerType,apiType,baseUrl,modelIdsJson,
                   selectedModelId,testState,testedAt,createdAt,updatedAt)
                   VALUES ('p1','我的 Kimi','kimi','openai_compatible','https://api.moonshot.cn/v1',
                   '["kimi-k3","kimi-k2"]','kimi-k3','success',111,100,200)"""
            )
            db.execSQL(
                """INSERT INTO sessions (id,title,configId,modelId,modelNameSnapshot,
                   sourceSessionId,sourceTitle,createdAt,updatedAt,deleteUntil)
                   VALUES ('s1','第一个会话','p1','kimi-k3','Kimi · kimi-k3',NULL,NULL,10,20,NULL)"""
            )
            db.execSQL(
                """INSERT INTO sessions (id,title,configId,modelId,modelNameSnapshot,
                   sourceSessionId,sourceTitle,createdAt,updatedAt,deleteUntil)
                   VALUES ('s2','副本','p1','kimi-k3','Kimi · kimi-k3','s1','第一个会话',30,40,NULL)"""
            )
            db.execSQL(
                """INSERT INTO messages (id,sessionId,role,seq,content,status,requestId,errorKind,createdAt)
                   VALUES ('m1','s1','user',1,'你好','DONE',NULL,NULL,11)"""
            )
            db.execSQL(
                """INSERT INTO messages (id,sessionId,role,seq,content,status,requestId,errorKind,createdAt)
                   VALUES ('m2','s1','assistant',2,'你好，有什么可以帮你？','DONE','r1',NULL,12)"""
            )
            db.execSQL(
                """INSERT INTO messages (id,sessionId,role,seq,content,status,requestId,errorKind,createdAt)
                   VALUES ('m3','s1','assistant',3,'还没写完的一半','STOPPED','r2',NULL,13)"""
            )
            db.execSQL("INSERT INTO drafts (sessionId,text,updatedAt) VALUES ('s1','未发送的草稿',50)")
            db.execSQL("INSERT INTO drafts (sessionId,text,updatedAt) VALUES ('new','空聊天页草稿',51)")
            db.execSQL(
                """INSERT INTO catalog_models (providerType,modelId,desc,updatedAt)
                   VALUES ('kimi','kimi-k3','长上下文',60)"""
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            name, 5, true, *DatabaseMigrations.ALL
        )

        migrated.query("SELECT COUNT(*) FROM providers").use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
        migrated.query(
            "SELECT id,title,configId,modelId,sourceSessionId,sourceTitle,deleteUntil " +
                "FROM sessions ORDER BY id"
        ).use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("s1", c.getString(0))
            assertEquals("第一个会话", c.getString(1))
            assertEquals("p1", c.getString(2))
            assertEquals("kimi-k3", c.getString(3))
            assertTrue(c.isNull(4))
            assertTrue(c.isNull(6))
            c.moveToNext()
            assertEquals("s2", c.getString(0))
            assertEquals("s1", c.getString(4))
            assertEquals("第一个会话", c.getString(5))
        }
        migrated.query(
            "SELECT id,content,status,seq FROM messages WHERE sessionId='s1' ORDER BY seq"
        ).use { c ->
            assertEquals(3, c.count)
            c.moveToFirst()
            assertEquals("你好", c.getString(1))
            assertEquals("DONE", c.getString(2))
            assertEquals(1, c.getInt(3))
            c.moveToNext()
            assertEquals("你好，有什么可以帮你？", c.getString(1))
            c.moveToNext()
            assertEquals("还没写完的一半", c.getString(1))
            assertEquals("STOPPED", c.getString(2))
        }
        // 草稿正文与空聊天页独立草稿都要在，升级后版本列从 0 起算
        migrated.query("SELECT sessionId,text,version FROM drafts ORDER BY sessionId").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("new", c.getString(0))
            assertEquals("空聊天页草稿", c.getString(1))
            assertEquals(0L, c.getLong(2))
            c.moveToNext()
            assertEquals("s1", c.getString(0))
            assertEquals("未发送的草稿", c.getString(1))
        }
        migrated.query("SELECT COUNT(*) FROM provider_keys").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM catalog_models").use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
        migrated.close()
    }

    /**
     * v1 没有留下任何工程产物，无法核对它的确切结构，
     * 所以用一个“比 v2 少字段少表”的旧库来验证补齐逻辑：
     * 缺少的列和表由迁移补上，已有数据一条都不能少。
     */
    @Test
    fun 从v1升级_补齐缺失结构且保留原有数据() {
        context.deleteDatabase(V1_DB)
        context.openOrCreateDatabase(V1_DB, Context.MODE_PRIVATE, null).use { v1 ->
            v1.execSQL(
                """CREATE TABLE `providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL,
                   `baseUrl` TEXT NOT NULL, `selectedModelId` TEXT NOT NULL,
                   `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
            )
            // v1 的 sessions 没有 sourceSessionId / sourceTitle / deleteUntil
            v1.execSQL(
                """CREATE TABLE `sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL,
                   `configId` TEXT, `modelId` TEXT NOT NULL, `modelNameSnapshot` TEXT NOT NULL,
                   `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
            )
            v1.execSQL(
                """CREATE TABLE `messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL,
                   `role` TEXT NOT NULL, `seq` INTEGER NOT NULL, `content` TEXT NOT NULL,
                   `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
            )
            v1.execSQL(
                """CREATE TABLE `drafts` (`sessionId` TEXT NOT NULL, `text` TEXT NOT NULL,
                   `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))"""
            )
            // v1 完全没有 catalog_models 表
            v1.execSQL(
                """INSERT INTO providers (id,name,baseUrl,selectedModelId,createdAt,updatedAt)
                   VALUES ('p1','旧配置','https://api.deepseek.com','deepseek-chat',1,2)"""
            )
            v1.execSQL(
                """INSERT INTO sessions (id,title,configId,modelId,modelNameSnapshot,createdAt,updatedAt)
                   VALUES ('s1','旧会话','p1','deepseek-chat','DeepSeek · deepseek-chat',1,2)"""
            )
            v1.execSQL(
                """INSERT INTO messages (id,sessionId,role,seq,content,status,createdAt)
                   VALUES ('m1','s1','user',1,'旧提问','DONE',1)"""
            )
            v1.execSQL(
                """INSERT INTO messages (id,sessionId,role,seq,content,status,createdAt)
                   VALUES ('m2','s1','assistant',2,'旧回复','DONE',2)"""
            )
            v1.execSQL("INSERT INTO drafts (sessionId,text,updatedAt) VALUES ('s1','旧草稿',3)")
            v1.version = 1
        }

        // 用真实迁移链打开；schema 不符合 v3 期望时 Room 会在打开时直接抛错
        val db = Room.databaseBuilder(context, AppDatabase::class.java, V1_DB)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
        db.openHelper.writableDatabase

        val chat = ChatRepository(db)
        runBlocking {
            assertEquals(2, chat.messageCount("s1"))
            val messages = chat.messagesOf("s1")
            assertEquals(2, messages.size)
            assertEquals("旧提问", messages[0].content)
            assertEquals("旧回复", messages[1].content)
            assertEquals("旧草稿", chat.draftFor("s1"))
            assertEquals(1, db.providerDao().count())
            assertEquals("旧配置", db.providerDao().byId("p1")?.name)
            assertEquals("旧会话", db.sessionDao().byId("s1")?.title)
        }
        // 补齐出来的列可以正常写入
        runBlocking {
            db.sessionDao().setDeleteUntil("s1", 999L)
            assertEquals(999L, db.sessionDao().byId("s1")?.deleteUntil)
            // 新表可用
            db.providerKeyDao().upsert(ProviderKeyEntity("p1", "cipher", 1))
            assertEquals(1, db.providerKeyDao().count("p1"))
        }
        db.close()
    }

    /**
     * v3 → v4：新增"配置自己的查询结果"与"测试记录"两张表，并给配置加地址／凭证版本。
     * 已有的配置、密钥、会话、消息、草稿一条都不能少，公共目录保持原样。
     */
    @Test
    fun 从v3升级到最新_新增两张表且原有数据不动() {
        val name = "mig34.db"
        helper.createDatabase(name, 3).use { db ->
            // v3 的 providers 还没有地址／凭证版本列，这两列由迁移补上
            db.execSQL(
                """INSERT INTO providers (id,name,providerType,apiType,baseUrl,modelIdsJson,
                   selectedModelId,testState,testedAt,createdAt,updatedAt)
                   VALUES ('p1','DeepSeek 配置','deepseek','openai_compatible','https://api.deepseek.com',
                   '["deepseek-flash"]','deepseek-flash','success',111,100,200)"""
            )
            db.execSQL(
                """INSERT INTO provider_keys (providerId,ciphertext,updatedAt)
                   VALUES ('p1','cipher-blob',300)"""
            )
            db.execSQL(
                """INSERT INTO sessions (id,title,configId,modelId,modelNameSnapshot,
                   sourceSessionId,sourceTitle,createdAt,updatedAt,deleteUntil)
                   VALUES ('s1','会话','p1','deepseek-flash','DeepSeek · deepseek-flash',NULL,NULL,10,20,NULL)"""
            )
            db.execSQL(
                """INSERT INTO messages (id,sessionId,role,seq,content,status,requestId,errorKind,createdAt)
                   VALUES ('m1','s1','assistant',1,'回复正文','DONE','r1',NULL,12)"""
            )
            db.execSQL("INSERT INTO drafts (sessionId,text,version,updatedAt) VALUES ('s1','草稿正文',3,50)")
            db.execSQL(
                """INSERT INTO catalog_models (providerType,modelId,desc,updatedAt)
                   VALUES ('deepseek','deepseek-flash','',60)"""
            )
        }

        val migrated = helper.runMigrationsAndValidate(name, 5, true, *DatabaseMigrations.ALL)

        // 新表存在且可写
        migrated.execSQL(
            """INSERT INTO config_model_snapshots (configId,modelId,endpointRevision,credentialRevision,queriedAt)
               VALUES ('p1','deepseek-flash',0,0,1)"""
        )
        migrated.execSQL(
            """INSERT INTO model_verifications (configId,modelId,endpointRevision,credentialRevision,success,errorKind,testedAt)
               VALUES ('p1','deepseek-flash',0,0,1,NULL,1)"""
        )
        // 旧数据逐项保留
        migrated.query(
            "SELECT name,selectedModelId,testState,endpointRevision,credentialRevision FROM providers"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("DeepSeek 配置", c.getString(0))
            assertEquals("deepseek-flash", c.getString(1))
            assertEquals("success", c.getString(2))
            assertEquals(0L, c.getLong(3))
            assertEquals(0L, c.getLong(4))
        }
        migrated.query("SELECT ciphertext FROM provider_keys WHERE providerId='p1'").use {
            it.moveToFirst(); assertEquals("cipher-blob", it.getString(0))
        }
        migrated.query("SELECT content FROM messages WHERE id='m1'").use {
            it.moveToFirst(); assertEquals("回复正文", it.getString(0))
        }
        migrated.query("SELECT text,version FROM drafts WHERE sessionId='s1'").use {
            it.moveToFirst()
            assertEquals("草稿正文", it.getString(0))
            assertEquals(3L, it.getLong(1))
        }
        migrated.query("SELECT COUNT(*) FROM catalog_models").use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
        migrated.close()
    }

    /** 迁移得到的结构和全新安装得到的结构必须一致，否则 Room 校验早晚会失败 */
    @Test
    fun 从v4升级到v5_新增缓存与Key表且原有数据不动() {
        val name = "mig45.db"
        helper.createDatabase(name, 4).use { db ->
            db.execSQL(
                """INSERT INTO providers (id,name,providerType,apiType,baseUrl,modelIdsJson,
                   selectedModelId,testState,testedAt,createdAt,updatedAt,endpointRevision,credentialRevision)
                   VALUES ('p1','DeepSeek 配置','deepseek','openai_compatible','https://api.deepseek.com',
                   '["deepseek-flash"]','deepseek-flash','success',111,100,200,1,1)"""
            )
            db.execSQL(
                """INSERT INTO sessions (id,title,configId,modelId,modelNameSnapshot,
                   sourceSessionId,sourceTitle,createdAt,updatedAt,deleteUntil)
                   VALUES ('s1','会话','p1','deepseek-flash','DeepSeek · deepseek-flash',NULL,NULL,10,20,NULL)"""
            )
            db.execSQL(
                """INSERT INTO messages (id,sessionId,role,seq,content,status,requestId,errorKind,createdAt)
                   VALUES ('m1','s1','assistant',1,'回复正文','DONE','r1',NULL,12)"""
            )
            db.execSQL(
                """INSERT INTO catalog_models (providerType,modelId,desc,updatedAt)
                   VALUES ('deepseek','deepseek-flash','',60)"""
            )
            db.execSQL(
                """INSERT INTO config_model_snapshots (configId,modelId,endpointRevision,credentialRevision,queriedAt)
                   VALUES ('p1','deepseek-flash',1,1,70)"""
            )
        }

        val migrated = helper.runMigrationsAndValidate(name, 5, true, *DatabaseMigrations.ALL)

        // 新表存在且可写
        migrated.execSQL(
            """INSERT INTO app_model_lists (providerType,modelId,fetchedAt)
               VALUES ('deepseek','deepseek-flash',80)"""
        )
        migrated.execSQL(
            """INSERT INTO app_provider_keys (providerType,ciphertext,updatedAt)
               VALUES ('deepseek','cipher-app',90)"""
        )
        // 旧数据逐项保留
        migrated.query(
            "SELECT name,selectedModelId,testState,endpointRevision,credentialRevision FROM providers"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("DeepSeek 配置", c.getString(0))
            assertEquals("deepseek-flash", c.getString(1))
            assertEquals("success", c.getString(2))
            assertEquals(1L, c.getLong(3))
            assertEquals(1L, c.getLong(4))
        }
        migrated.query("SELECT content FROM messages WHERE id='m1'").use {
            it.moveToFirst(); assertEquals("回复正文", it.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM catalog_models").use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
        migrated.query(
            "SELECT modelId FROM config_model_snapshots WHERE configId='p1'"
        ).use {
            it.moveToFirst(); assertEquals("deepseek-flash", it.getString(0))
        }
        migrated.close()
        context.deleteDatabase(name)
    }

    /** 迁移得到的结构和全新安装得到的结构必须一致，否则 Room 校验早晚会失败 */
    @Test
    fun 迁移后的结构与全新安装一致() {
        val migratedName = "mig-schema.db"
        helper.createDatabase(migratedName, 2).use { }
        val migrated = helper.runMigrationsAndValidate(
            migratedName, 5, true, *DatabaseMigrations.ALL
        )
        val migratedSchema = schemaOf(migrated)
        migrated.close()

        context.deleteDatabase(FRESH_DB)
        val fresh = Room.databaseBuilder(context, AppDatabase::class.java, FRESH_DB).build()
        val freshSchema = schemaOf(fresh.openHelper.writableDatabase)
        fresh.close()

        assertEquals(freshSchema, migratedSchema)
        context.deleteDatabase(migratedName)
    }

    /**
     * 逐表读出列定义与索引定义，用来比较两份库的结构。
     *
     * 列按名字排序后比较：SQLite 的 ALTER TABLE ADD COLUMN 会把新列追加到末尾，
     * 迁移库与全新安装库的物理列顺序因此可能不同，而 Room 是按列名校验的，
     * 顺序差异不影响任何行为，不应该算作结构不一致。
     */
    private fun schemaOf(db: SupportSQLiteDatabase): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { tables ->
            val names = buildList {
                while (tables.moveToNext()) add(tables.getString(0))
            }
            for (name in names) {
                if (name == "room_master_table" || name == "android_metadata") continue
                val cols = buildList {
                    db.query("PRAGMA table_info(`$name`)").use { c ->
                        val n = c.getColumnIndex("name")
                        val t = c.getColumnIndex("type")
                        val notNull = c.getColumnIndex("notnull")
                        val dflt = c.getColumnIndex("dflt_value")
                        val pk = c.getColumnIndex("pk")
                        while (c.moveToNext()) {
                            add(
                                "${c.getString(n)}:${c.getString(t)}:" +
                                    "${c.getInt(notNull)}:${c.getString(dflt)}:${c.getInt(pk)}"
                            )
                        }
                    }
                }.sorted()
                val indices = buildList {
                    db.query(
                        "SELECT name, sql FROM sqlite_master WHERE type='index' AND tbl_name='$name'" +
                            " AND sql IS NOT NULL ORDER BY name"
                    ).use { c ->
                        while (c.moveToNext()) add("${c.getString(0)}=${c.getString(1)}")
                    }
                }
                result[name] = cols + indices
            }
        }
        return result
    }

    private companion object {
        const val V1_DB = "legacy-v1.db"
        const val FRESH_DB = "fresh-v3.db"
    }
}

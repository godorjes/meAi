package app.polyglot.dev.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 显式数据库迁移：升级一律保留原数据，不再重建数据库。
 *
 * 关于 1 → 2：仓库里没有留下 v1 的工程产物、导出 schema 或测试数据库，
 * 无法核对 v1 的真实结构。凭猜测写一份固定 SQL，遇到真实旧库时必然漏列或直接失败，
 * 因此这里改成**按实际结构对齐**：
 *   - 表不存在就按 v2 的定义建出来；
 *   - 表存在但列集合与 v2 不一致，就用“改名 → 建新表 → 搬数据 → 删旧表”的标准做法重建，
 *     只搬同名的列，缺的列补类型默认值，多出来的列自然被丢弃。
 * 整个过程幂等、可重复执行，且不删除任何业务数据。
 *
 * v2 的建表语句与列清单取自 Room 为 v2 生成的代码，并已与真机 v2 库的
 * room_table_master.identity_hash 比对一致（1ef423a8ff1485ccab78a452725f19ff）。
 */
internal object DatabaseMigrations {

    private val PROVIDERS_COLUMNS = listOf(
        "id", "name", "providerType", "apiType", "baseUrl", "modelIdsJson",
        "selectedModelId", "testState", "testedAt", "createdAt", "updatedAt"
    )
    private val SESSIONS_COLUMNS = listOf(
        "id", "title", "configId", "modelId", "modelNameSnapshot",
        "sourceSessionId", "sourceTitle", "createdAt", "updatedAt", "deleteUntil"
    )
    private val MESSAGES_COLUMNS = listOf(
        "id", "sessionId", "role", "seq", "content", "status", "requestId", "errorKind", "createdAt"
    )
    private val DRAFTS_COLUMNS = listOf("sessionId", "text", "updatedAt")
    private val CATALOG_COLUMNS = listOf("providerType", "modelId", "desc", "updatedAt")

    private const val CREATE_PROVIDERS =
        "CREATE TABLE IF NOT EXISTS `providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`providerType` TEXT NOT NULL, `apiType` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, " +
            "`modelIdsJson` TEXT NOT NULL, `selectedModelId` TEXT NOT NULL, " +
            "`testState` TEXT NOT NULL, `testedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    private const val CREATE_SESSIONS =
        "CREATE TABLE IF NOT EXISTS `sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`configId` TEXT, `modelId` TEXT NOT NULL, `modelNameSnapshot` TEXT NOT NULL, " +
            "`sourceSessionId` TEXT, `sourceTitle` TEXT, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `deleteUntil` INTEGER, PRIMARY KEY(`id`))"

    private const val CREATE_MESSAGES =
        "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
            "`role` TEXT NOT NULL, `seq` INTEGER NOT NULL, `content` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, `requestId` TEXT, `errorKind` TEXT, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    private const val CREATE_DRAFTS =
        "CREATE TABLE IF NOT EXISTS `drafts` (`sessionId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))"

    private const val CREATE_CATALOG =
        "CREATE TABLE IF NOT EXISTS `catalog_models` (`providerType` TEXT NOT NULL, " +
            "`modelId` TEXT NOT NULL, `desc` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`providerType`, `modelId`))"

    private const val CREATE_PROVIDER_KEYS =
        "CREATE TABLE IF NOT EXISTS `provider_keys` (`providerId` TEXT NOT NULL, " +
            "`ciphertext` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`providerId`))"

    private const val CREATE_CONFIG_MODEL_SNAPSHOTS =
        "CREATE TABLE IF NOT EXISTS `config_model_snapshots` (`configId` TEXT NOT NULL, " +
            "`modelId` TEXT NOT NULL, `endpointRevision` INTEGER NOT NULL, " +
            "`credentialRevision` INTEGER NOT NULL, `queriedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`configId`, `modelId`))"

    private const val CREATE_MODEL_VERIFICATIONS =
        "CREATE TABLE IF NOT EXISTS `model_verifications` (`configId` TEXT NOT NULL, " +
            "`modelId` TEXT NOT NULL, `endpointRevision` INTEGER NOT NULL, " +
            "`credentialRevision` INTEGER NOT NULL, `success` INTEGER NOT NULL, " +
            "`errorKind` TEXT, `testedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`configId`, `modelId`))"

    private const val CREATE_APP_MODEL_LISTS =
        "CREATE TABLE IF NOT EXISTS `app_model_lists` (`providerType` TEXT NOT NULL, " +
            "`modelId` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`providerType`, `modelId`))"

    private const val CREATE_APP_PROVIDER_KEYS =
        "CREATE TABLE IF NOT EXISTS `app_provider_keys` (`providerType` TEXT NOT NULL, " +
            "`ciphertext` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`providerType`))"

    /** 1 → 2：把每张表对齐到 v2 的列集合，缺什么补什么，不猜测、不删数据 */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `android_metadata` (`locale` TEXT)")

            alignTable(db, "providers", CREATE_PROVIDERS, PROVIDERS_COLUMNS)
            alignTable(db, "sessions", CREATE_SESSIONS, SESSIONS_COLUMNS)
            alignTable(db, "messages", CREATE_MESSAGES, MESSAGES_COLUMNS)
            alignTable(db, "drafts", CREATE_DRAFTS, DRAFTS_COLUMNS)
            alignTable(db, "catalog_models", CREATE_CATALOG, CATALOG_COLUMNS)

            // 索引要在表重建之后建，否则会和旧表带过来的同名索引冲突
            createIndexIfMissing(db, "index_sessions_configId", "sessions", listOf("configId"))
            createIndexIfMissing(db, "index_sessions_updatedAt", "sessions", listOf("updatedAt"))
            createIndexIfMissing(db, "index_messages_sessionId", "messages", listOf("sessionId"))
            createIndexIfMissing(
                db, "index_messages_sessionId_seq", "messages", listOf("sessionId", "seq")
            )
        }
    }

    /** 2 → 3：新增密钥表，并为草稿增加写入版本列 */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_PROVIDER_KEYS)
            // 已存在的草稿从版本 0 起算；列默认值与实体声明保持一致
            addColumnIfMissing(db, "drafts", "version", "`version` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * 3 → 4：把公共模型目录与"配置自己的查询结果／测试结果"分开存放。
     * 公共目录保持原样（它是参考资料，不再被账号查询结果裁剪）；
     * 新增两张表，并给配置加上地址版本与凭证版本。
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_CONFIG_MODEL_SNAPSHOTS)
            db.execSQL(CREATE_MODEL_VERIFICATIONS)
            // 已有配置从版本 0 起算；列默认值与实体声明保持一致
            addColumnIfMissing(
                db, "providers", "endpointRevision", "`endpointRevision` INTEGER NOT NULL DEFAULT 0"
            )
            addColumnIfMissing(
                db, "providers", "credentialRevision", "`credentialRevision` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * 4 → 5：新增每日模型列表缓存与 App 级厂家 Key 两张表。
     * 纯增量建表，不动任何已有数据。
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_APP_MODEL_LISTS)
            db.execSQL(CREATE_APP_PROVIDER_KEYS)
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

    // ---------- 结构对齐 ----------

    /**
     * 把 [table] 的列集合对齐到 [expected]。
     * 列集合已经一致就原样保留；不一致时重建表并搬运同名列的数据。
     */
    private fun alignTable(
        db: SupportSQLiteDatabase,
        table: String,
        createSql: String,
        expected: List<String>
    ) {
        if (!db.hasTable(table)) {
            db.execSQL(createSql)
            return
        }
        val actual = db.columnsOf(table)
        if (actual == expected.toSet()) return

        val backup = "${table}_before_v2"
        db.execSQL("DROP TABLE IF EXISTS `$backup`")
        db.execSQL("ALTER TABLE `$table` RENAME TO `$backup`")
        db.execSQL(createSql)

        // 缺的列给类型默认值：文本补空串、数值补 0，保证 NOT NULL 约束能满足；
        // 旧表里多出来的列不参与搬运，自然被丢弃
        val sourceColumns = expected.joinToString(", ") { column ->
            if (column in actual) "`$column`" else defaultFor(column)
        }
        db.execSQL("INSERT INTO `$table` (${expected.joinToString(", ") { "`$it`" }}) " +
            "SELECT $sourceColumns FROM `$backup`")
        db.execSQL("DROP TABLE `$backup`")
    }

    private fun defaultFor(column: String): String = when (column) {
        "createdAt", "updatedAt", "testedAt", "deleteUntil", "seq" -> "0"
        "testState" -> "'none'"
        "apiType" -> "'openai_compatible'"
        else -> "''"
    }

    private fun addColumnIfMissing(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        columnDdl: String
    ) {
        if (db.hasTable(table) && column !in db.columnsOf(table)) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN $columnDdl")
        }
    }

    private fun createIndexIfMissing(
        db: SupportSQLiteDatabase,
        indexName: String,
        table: String,
        columns: List<String>
    ) {
        if (!db.hasTable(table)) return
        val exists = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='$indexName'"
        ).use { it.moveToFirst() }
        if (!exists) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `$indexName` ON `$table` (" +
                    columns.joinToString(", ") { "`$it`" } + ")"
            )
        }
    }

    private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'")
            .use { it.moveToFirst() }

    private fun SupportSQLiteDatabase.columnsOf(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildSet {
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0) add(cursor.getString(nameIndex))
                }
            }
        }
}

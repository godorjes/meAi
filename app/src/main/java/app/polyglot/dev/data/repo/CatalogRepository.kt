package app.polyglot.dev.data.repo

import android.content.Context
import androidx.room.withTransaction
import app.polyglot.dev.data.crypto.KeyCrypto
import app.polyglot.dev.data.local.AppDatabase
import app.polyglot.dev.data.local.AppModelListEntity
import app.polyglot.dev.data.local.AppProviderKeyEntity
import app.polyglot.dev.data.local.CatalogModelEntity
import app.polyglot.dev.data.net.CatalogFetcher
import app.polyglot.dev.data.net.ModelListClient
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * 公共模型目录：内置 JSON + 我们维护的远程公开目录。
 *
 * 这里只放"公共参考资料"，与某个账号查询到的结果严格分开：
 * - 账号查询结果存在 config_model_snapshots（见 ConfigRepository），不写这张表；
 * - 因此一个账号查不到某个模型，不会删掉别人或公共目录里的同名条目。
 *
 * 页面始终先读本地（内置或上次校验通过的远程版本），网络只用于尽力更新，
 * 不是页面可用的前提。
 *
 * 每日定时任务拉取的厂家模型缓存（app_model_lists）也在这里管理：
 * 用户打开选择器只读缓存，不再每次在线请求。
 */
class CatalogRepository(
    private val db: AppDatabase,
    private val appContext: Context,
    private val keyCrypto: KeyCrypto,
    private val fetcher: CatalogFetcher = CatalogFetcher(),
    private val modelListClient: ModelListClient = ModelListClient()
) {
    private val catalogDao get() = db.catalogDao()
    private val appListDao get() = db.appModelListDao()
    private val appKeyDao get() = db.appProviderKeyDao()

    fun observeByProvider(providerType: String): Flow<List<CatalogModelEntity>> =
        catalogDao.observeByProvider(providerType)

    /** 公共目录整体（含所有厂家），供选择器按当前厂家过滤 */
    fun observeAll(): Flow<List<CatalogModelEntity>> = catalogDao.observeAll()

    suspend fun listByProvider(providerType: String): List<CatalogModelEntity> =
        catalogDao.listByProvider(providerType)

    /** 首次安装立即读取随包内置的已核实目录；表里已有内容就不覆盖 */
    suspend fun seedIfEmpty(currentVersion: Long = 0L): Boolean {
        if (catalogDao.count() > 0) return false
        val raw = readBuiltIn() ?: return false
        val parsed = PublicCatalog.parse(raw, currentVersion = 0L) ?: return false
        catalogDao.upsertAll(parsed.entries)
        onVersionApplied?.invoke(parsed.catalogVersion)
        return true
    }

    /** 内置目录版本更新时替换已生效的资料；版本不新就什么都不做 */
    suspend fun syncBuiltIn(currentVersion: Long): Boolean {
        val raw = readBuiltIn() ?: return false
        val parsed = PublicCatalog.parse(raw, currentVersion) ?: return false
        catalogDao.upsertAll(parsed.entries)
        onVersionApplied?.invoke(parsed.catalogVersion)
        return true
    }

    /** 目录版本落地后的回调（用于记录已生效版本） */
    var onVersionApplied: (suspend (Long) -> Unit)? = null

    /**
     * 用一份远程目录更新公共资料。
     *
     * 下载、解析或校验任何一步失败都返回 false 并**保留当前目录**：
     * 损坏、未知 schema、重复条目、版本不新，都不能覆盖已经有效的目录。
     * 校验通过才整体替换该目录覆盖到的厂家条目。
     */
    suspend fun applyRemoteCatalog(raw: String, currentVersion: Long): Boolean {
        val parsed = PublicCatalog.parse(raw, currentVersion) ?: return false
        db.withTransaction {
            catalogDao.upsertAll(parsed.entries)
        }
        return true
    }

    /**
     * 尽力更新公共目录。
     * 地址未配置（本轮未部署远程目录服务）、下载失败、解析或校验失败，
     * 都返回 false 并**保留当前目录**：失效的远程数据不能覆盖已经有效的目录。
     */
    suspend fun refreshFromRemote(url: String = PUBLIC_CATALOG_URL, currentVersion: Long = 0L): Boolean {
        val raw = fetcher.fetch(url) ?: return false
        return applyRemoteCatalog(raw, currentVersion)
    }

    private fun readBuiltIn(): String? = runCatching {
        appContext.assets.open("model_catalog.json").bufferedReader().use { it.readText() }
    }.getOrNull()

    // ---------- 每日模型列表缓存（app_model_lists） ----------

    /** 选择器读的本地缓存：每日任务拉取的各厂家最新模型 */
    fun observeAppModelLists(): Flow<List<AppModelListEntity>> = appListDao.observeAll()

    suspend fun hasAppModelCache(): Boolean = appListDao.count() > 0

    /**
     * 首次安装把随包的 App 级厂家 Key 播进数据库（Keystore 加密，只播缺失的）。
     * 这些 Key 只用于每日拉取模型列表，不参与用户聊天请求。
     */
    suspend fun seedAppKeysIfMissing() {
        val raw = runCatching {
            appContext.assets.open("app_provider_keys.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        val map = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        for (providerType in map.keys()) {
            if (appKeyDao.byId(providerType) != null) continue
            val plain = map.optString(providerType).trim()
            if (plain.isEmpty()) continue
            runCatching {
                appKeyDao.upsert(AppProviderKeyEntity(providerType, keyCrypto.encrypt(plain), now))
            }
        }
    }

    /**
     * 每日同步：逐厂家拉取最新前五并整家替换缓存。
     * 一家失败不影响其他家；失败或空结果一律保留旧缓存，不清空。
     */
    suspend fun syncTopModels() {
        val now = System.currentTimeMillis()
        for (providerType in modelListClient.fetchableEndpoints.keys) {
            runCatching {
                val ciphertext = appKeyDao.byId(providerType)?.ciphertext ?: return@runCatching
                val apiKey = keyCrypto.decryptOrNull(ciphertext) ?: return@runCatching
                val ids = modelListClient.fetchTopModels(providerType, apiKey)
                if (ids.isEmpty()) return@runCatching
                db.withTransaction {
                    appListDao.deleteForProvider(providerType)
                    appListDao.upsertAll(ids.map { AppModelListEntity(providerType, it, now) })
                }
            }
        }
    }

    companion object {
        /** 远程公共目录地址：维护端部署后填写；未部署时只用内置目录与厂家查询 */
        const val PUBLIC_CATALOG_URL = ""
    }
}

/**
 * 公共目录的解析与校验。
 *
 * 规格要求：对格式、版本、大小、重复 ID、支持的厂家和字段进行校验，校验通过才原子替换。
 * 这里做成纯函数，便于直接对"损坏／未知 schema／重复条目／过期版本"做测试，不需要真的联网。
 */
object PublicCatalog {

    /** 目录里允许出现的厂家；未知厂家一律判为无效，避免目录数据凭空出现新来源 */
    private val KNOWN_PROVIDERS = setOf("openai", "glm", "deepseek", "kimi", "qwen")

    private const val MAX_BYTES = 256 * 1024
    private const val MAX_MODELS_PER_PROVIDER = 500
    private const val MAX_DESC_LENGTH = 200

    data class Parsed(
        val schemaVersion: Int,
        val catalogVersion: Long,
        val publishedAt: Long,
        val entries: List<CatalogModelEntity>
    )

    /**
     * @param currentVersion 当前已生效的目录版本；远程版本不更新则判为无效（返回 null）
     * @return 校验通过的结果；任何一项不通过都返回 null，调用方保留现有目录
     */
    fun parse(raw: String, currentVersion: Long): Parsed? {
        if (raw.isBlank() || raw.length > MAX_BYTES) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != SUPPORTED_SCHEMA) return null

        val catalogVersion = root.optLong("catalogVersion", -1L)
        if (catalogVersion <= 0L || catalogVersion <= currentVersion) return null

        val providers = root.optJSONObject("providers") ?: return null
        val now = System.currentTimeMillis()

        val entries = mutableListOf<CatalogModelEntity>()
        val seen = mutableSetOf<String>()
        for (providerType in providers.keys()) {
            if (providerType !in KNOWN_PROVIDERS) return null
            val arr = providers.optJSONArray(providerType) ?: return null
            if (arr.length() > MAX_MODELS_PER_PROVIDER) return null
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: return null
                val id = obj.optString("id").trim()
                if (id.isEmpty() || id.length > MAX_DESC_LENGTH) return null
                // 重复 ID 视为目录损坏：宁可不更新，也不要把重复条目写进库
                if (!seen.add("$providerType/$id")) return null
                val desc = obj.optString("desc").trim().take(MAX_DESC_LENGTH)
                entries += CatalogModelEntity(providerType, id, desc, now)
            }
        }
        if (entries.isEmpty()) return null
        return Parsed(schemaVersion, catalogVersion, root.optLong("publishedAt", 0L), entries)
    }

    const val SUPPORTED_SCHEMA = 1
}

package app.polyglot.dev.data.repo

import androidx.room.withTransaction
import app.polyglot.dev.data.crypto.KeyCrypto
import app.polyglot.dev.data.local.AppDatabase
import app.polyglot.dev.data.local.ConfigModelSnapshotEntity
import app.polyglot.dev.data.local.ModelVerificationEntity
import app.polyglot.dev.data.local.ProviderEntity
import app.polyglot.dev.data.local.ProviderKeyEntity
import app.polyglot.dev.data.net.ErrorKind
import app.polyglot.dev.data.net.ModelQuerySupport
import app.polyglot.dev.data.net.ChatStreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

data class ProviderPreset(
    val type: String,
    val label: String,
    val defaultBaseUrl: String,
    val keyHelpUrl: String,
    /** 该厂家是否提供可靠的官方模型列表接口；未核实的不去猜地址试探 */
    val modelQuery: ModelQuerySupport,
    /** 是否出现在「添加配置」的厂家选择里。保留非可选厂家是为了已存在的旧配置还能正确显示 */
    val selectable: Boolean = true
)

object ProviderPresets {
    // 2026-09-15 按官方文档核对：地址与凭证所属平台必须匹配
    val all = listOf(
        ProviderPreset(
            "glm", "GLM（智谱）", "https://open.bigmodel.cn/api/paas/v4",
            "https://bigmodel.cn/usercenter/proj-mgmt/apikeys", ModelQuerySupport.SUPPORTED
        ),
        ProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com",
            "https://platform.deepseek.com/api_keys", ModelQuerySupport.SUPPORTED
        ),
        ProviderPreset(
            "kimi", "Kimi", "https://api.moonshot.cn/v1",
            "https://platform.kimi.com/console/api-keys", ModelQuerySupport.SUPPORTED
        ),
        // 已移除千问新增入口；保留定义以兼容历史配置与会话。
        ProviderPreset(
            "qwen", "千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://bailian.console.aliyun.com/?apiKey=1", ModelQuerySupport.UNVERIFIED, selectable = false
        ),
        ProviderPreset(
            "custom", "自定义", "", "", ModelQuerySupport.USER_DEFINED
        ),
        // 已从选择列表移除；保留定义，让历史配置仍能正确显示名称、地址与帮助入口
        ProviderPreset(
            "openai", "OpenAI", "https://api.openai.com/v1",
            "https://platform.openai.com/api-keys", ModelQuerySupport.SUPPORTED, selectable = false
        )
    )

    /** 「添加配置」里可选的厂家 */
    val selectable: List<ProviderPreset> get() = all.filter { it.selectable }

    fun byType(type: String): ProviderPreset = all.firstOrNull { it.type == type } ?: all.last()
}

enum class TestState { NONE, SUCCESS, FAIL }

/** 保存配置的结果：被生成任务占用时不允许改动 */
sealed interface SaveProviderResult {
    data class Saved(val entity: ProviderEntity) : SaveProviderResult
    data object Busy : SaveProviderResult
    /** 普通流程允许自动命名，但仍要求名称最终有效 */
    data object InvalidName : SaveProviderResult
}

/** 某个模型相对于"当前凭证查询结果"的来源状态，供界面标注不同证据 */
enum class ModelOrigin {
    /** 当前配置用当前地址与凭证查到过 */
    SERVICE_RETURNED,

    /** 配置里保存着、但本次查询没有返回 */
    NOT_RETURNED_THIS_TIME,

    /** 只来自公共参考目录，未证明当前配置可调用 */
    PUBLIC_REFERENCE
}

class ConfigRepository(
    private val db: AppDatabase,
    private val keyCrypto: KeyCrypto,
    private val client: ChatStreamClient
) {
    private val providerDao get() = db.providerDao()
    private val providerKeyDao get() = db.providerKeyDao()
    private val sessionDao get() = db.sessionDao()
    private val messageDao get() = db.messageDao()
    private val snapshotDao get() = db.configModelDao()
    private val verificationDao get() = db.verificationDao()

    /** 串行化保存：自动命名要在并发点击下也不产生重名配置 */
    private val saveMutex = Mutex()

    val providers: Flow<List<ProviderEntity>> = providerDao.observeAll()

    suspend fun providerById(id: String): ProviderEntity? = providerDao.byId(id)

    suspend fun hasProvider(): Boolean = providerDao.count() > 0

    /** 一次性快照（定时轮询等后台任务使用） */
    suspend fun snapshotProviders(): List<ProviderEntity> = providerDao.observeAll().first()

    /**
     * 读取 API Key：数据库密文是唯一事实来源。
     * 数据库还没有记录时会尝试从旧 SharedPreferences 迁移一次，兼容未走完迁移的升级。
     */
    suspend fun readApiKey(configId: String): String? = withContext(Dispatchers.IO) {
        providerKeyDao.byId(configId)?.let { row ->
            return@withContext keyCrypto.decryptOrNull(row.ciphertext)
        }
        val legacy = keyCrypto.legacyCiphertext(configId) ?: return@withContext null
        val plain = keyCrypto.decryptOrNull(legacy) ?: return@withContext null
        migrateLegacyKey(configId, legacy)
        plain
    }

    fun decodeModels(json: String): List<String> = runCatching {
        val arr = JSONArray(json)
        buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
    }.getOrDefault(emptyList())

    /**
     * 该配置可用的模型：配置保存的列表 ∪ 当前选中的模型。
     *
     * 选中的模型单独存放在 selectedModelId，**不再追加进列表**。
     * 旧实现每次换模型都把新模型并进列表，换几次就永久留下几个，
     * 于是"只配了一个模型"的配置在换模型列表里会显示成好几个。
     */
    fun modelsOf(entity: ProviderEntity): List<String> {
        val list = decodeModels(entity.modelIdsJson)
        val selected = entity.selectedModelId.trim()
        return if (selected.isNotEmpty() && selected !in list) list + selected else list
    }

    // ---------- 配置自己的官方查询结果（与公共目录分开） ----------

    /** 用当前地址与凭证查到过的模型；凭证或地址换过就为空，旧结果不算证据 */
    suspend fun queriedModels(configId: String): List<String> {
        val provider = providerDao.byId(configId) ?: return emptyList()
        return snapshotDao.listForConfig(configId)
            .filter {
                it.endpointRevision == provider.endpointRevision &&
                    it.credentialRevision == provider.credentialRevision
            }
            .map { it.modelId }
    }

    /**
     * 记录一次成功的官方查询。
     *
     * 结果按配置隔离写入快照表；同时把该配置的模型列表替换为"服务返回的模型 ∪ 当前选中模型"，
     * 既不会无限累积，也不会把用户已选中的模型弄丢。
     * **不动公共目录**：一个账号查不到某模型，不代表该模型在整个厂家下线。
     */
    suspend fun recordQueryResult(configId: String, modelIds: List<String>) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val provider = providerDao.byId(configId) ?: return@withTransaction
                val now = System.currentTimeMillis()
                val distinct = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

                val selected = provider.selectedModelId.trim()
                snapshotDao.pruneAfterQuery(configId, distinct, selected)
                if (distinct.isNotEmpty()) {
                    snapshotDao.upsertAll(
                        distinct.map {
                            ConfigModelSnapshotEntity(
                                configId = configId,
                                modelId = it,
                                endpointRevision = provider.endpointRevision,
                                credentialRevision = provider.credentialRevision,
                                queriedAt = now
                            )
                        }
                    )
                }
                // 列表替换为服务返回 ∪ 选中项，避免历史结果无限堆积
                val list = (distinct + selected).filter { it.isNotEmpty() }.distinct()
                if (provider.modelIdsJson != JSONArray(list).toString()) {
                    providerDao.upsert(
                        provider.copy(
                            modelIdsJson = JSONArray(list).toString(),
                            updatedAt = now
                        )
                    )
                }
            }
        }
    }

    /** 查询结果的来源状态：区分"服务返回"、"本次列表未返回"与"公共参考" */
    suspend fun originOf(
        entity: ProviderEntity,
        modelId: String,
        publicCatalogIds: Set<String>
    ): ModelOrigin {
        val queried = queriedModels(entity.id)
        return when {
            modelId in queried -> ModelOrigin.SERVICE_RETURNED
            modelId in modelsOf(entity) -> ModelOrigin.NOT_RETURNED_THIS_TIME
            modelId in publicCatalogIds -> ModelOrigin.PUBLIC_REFERENCE
            else -> ModelOrigin.PUBLIC_REFERENCE
        }
    }

    // ---------- 当前配置的测试记录 ----------

    /** 当前地址与凭证下，该模型的最近一次测试结果 */
    suspend fun verificationFor(configId: String, modelId: String): ModelVerificationEntity? {
        val provider = providerDao.byId(configId) ?: return null
        val row = verificationDao.byId(configId, modelId) ?: return null
        return if (row.endpointRevision == provider.endpointRevision &&
            row.credentialRevision == provider.credentialRevision
        ) row else null
    }

    /**
     * 记录一次聊天测试结果。写入 参数版本 = 当前地址与凭证版本，
     * 因此换了 Key 或地址后，界面不会再把旧结论当作当前参数的测试结果。
     */
    suspend fun recordVerification(configId: String, modelId: String, success: Boolean, kind: ErrorKind?) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val provider = providerDao.byId(configId) ?: return@withTransaction
                val now = System.currentTimeMillis()
                verificationDao.upsert(
                    ModelVerificationEntity(
                        configId = configId,
                        modelId = modelId,
                        endpointRevision = provider.endpointRevision,
                        credentialRevision = provider.credentialRevision,
                        success = success,
                        errorKind = kind?.name,
                        testedAt = now
                    )
                )
                // 列表页沿用配置上的汇总状态，避免再查一次
                providerDao.updateTestState(
                    configId, if (success) "success" else "fail", now, now
                )
            }
        }
    }

    /**
     * 保存配置与密钥。
     * 加密在事务之前完成：加密失败直接抛出，原配置一字不改。
     * 配置元数据与密文在同一事务提交，提交成功才算保存成功。
     *
     * 名称：普通流程不要求用户填写，留空则按厂家自动生成并在重复时加序号；
     * 高级设置里填了别名就用别名。
     */
    suspend fun saveProvider(
        existingId: String?,
        name: String,
        providerType: String,
        baseUrl: String,
        apiKey: String,
        modelIds: List<String>,
        selectedModelId: String
    ): SaveProviderResult = saveMutex.withLock {
        withContext(Dispatchers.IO) {
            if (ProviderPresets.all.none { it.type == providerType }) {
                return@withContext SaveProviderResult.InvalidName
            }
            val id = existingId ?: UUID.randomUUID().toString()
            val old = existingId?.let { providerDao.byId(it) }
            if (old != null && isProviderBusy(old.id)) return@withContext SaveProviderResult.Busy

            val trimmedBase = baseUrl.trim().removeSuffix("/")
            val keySame = old != null && readApiKey(old.id) == apiKey
            val endpointChanged = old == null || old.baseUrl != trimmedBase
            val credentialChanged = old == null || !keySame

            // 地址／凭证变了就推进版本：旧查询结果与旧测试结论随之失效
            val endpointRevision = if (endpointChanged) (old?.endpointRevision ?: 0L) + 1 else old!!.endpointRevision
            val credentialRevision =
                if (credentialChanged) (old?.credentialRevision ?: 0L) + 1 else old!!.credentialRevision

            // 事务之外先加密：失败则完全不进入写库流程
            val ciphertext = keyCrypto.encrypt(apiKey)

            val now = System.currentTimeMillis()
            val finalName = name.trim().ifBlank { autoName(providerType) }
            val selected = selectedModelId.trim()
            val list = (modelIds.map { it.trim() }.filter { it.isNotEmpty() } + selected).distinct()
            val testState = if (endpointChanged || credentialChanged) {
                "none"
            } else old.testState
            val testedAt = if (endpointChanged || credentialChanged) null else old.testedAt

            val entity = ProviderEntity(
                id = id,
                name = finalName,
                providerType = providerType,
                apiType = "openai_compatible",
                baseUrl = trimmedBase,
                modelIdsJson = JSONArray(list).toString(),
                selectedModelId = selected,
                testState = testState,
                testedAt = testedAt,
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
                endpointRevision = endpointRevision,
                credentialRevision = credentialRevision
            )
            db.withTransaction {
                providerDao.upsert(entity)
                providerKeyDao.upsert(ProviderKeyEntity(id, ciphertext, now))
                // 参数变了，旧测试结论不再是当前参数的证据
                if (endpointChanged || credentialChanged) verificationDao.deleteForConfig(id)
            }
            keyCrypto.removeLegacyCiphertext(id)
            SaveProviderResult.Saved(entity)
        }
    }

    /** 按厂家自动生成名称，重名时加序号；在保存互斥锁内计算，连点也不会重名 */
    private suspend fun autoName(providerType: String): String {
        val label = ProviderPresets.byType(providerType).label
        val base = "$label 配置"
        val existing = providerDao.allNames().toSet()
        if (base !in existing) return base
        var n = 2
        while ("$base $n" in existing) n++
        return "$base $n"
    }

    // ---------- 模型查询 ----------

    /** 该厂家是否值得尝试官方列表接口；未核实的厂家不去猜地址试探 */
    fun supportsModelQuery(providerType: String): Boolean =
        ProviderPresets.byType(providerType).modelQuery != ModelQuerySupport.UNVERIFIED

    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> =
        client.fetchModels(baseUrl, apiKey)

    /** 编辑页测试连接：直接使用表单值，不写数据库 */
    suspend fun testConnectionRaw(baseUrl: String, apiKey: String, modelId: String) {
        client.testChat(baseUrl, apiKey, modelId)
    }

    suspend fun testConnection(provider: ProviderEntity, modelId: String, apiKeyOverride: String? = null) {
        val key = apiKeyOverride ?: readApiKey(provider.id)
            ?: throw SecurityException("缺少 API Key")
        client.testChat(provider.baseUrl, key, modelId)
    }

    /** 该配置是否正被生成请求使用：使用中不允许编辑或删除 */
    suspend fun isProviderBusy(id: String): Boolean = messageDao.generatingCountForConfig(id) > 0

    /** 影响正在生成的配置不允许删除；返回 false 表示被拦截 */
    suspend fun canDeleteProvider(id: String): Boolean = !isProviderBusy(id)

    suspend fun deleteProvider(id: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            providerDao.delete(id)
            providerKeyDao.delete(id)
            snapshotDao.deleteForConfig(id)
            verificationDao.deleteForConfig(id)
            // 聊天保留模型名称快照，配置引用置空
            sessionDao.detachConfig(id)
        }
        keyCrypto.removeLegacyCiphertext(id)
    }

    // ---------- 历史密文迁移 ----------

    /**
     * 把旧 SharedPreferences 里的密文搬进数据库。可重复执行、可中断续跑：
     * 数据库已有该配置的密钥就跳过并清理旧密文；
     * 只有新记录提交成功、并且从数据库读回来能解密，才删除旧密文。
     * 返回本次真正迁移的条数。
     */
    suspend fun migrateLegacyKeys(): Int = withContext(Dispatchers.IO) {
        var moved = 0
        for (configId in keyCrypto.legacyConfigIds()) {
            // 配置已被删除：密钥失去归属，保留旧密文，不擅自丢弃
            if (providerDao.byId(configId) == null) continue
            val legacy = keyCrypto.legacyCiphertext(configId) ?: continue
            if (providerKeyDao.count(configId) > 0) {
                keyCrypto.removeLegacyCiphertext(configId)
                continue
            }
            // 先确认这份密文在当前 Keystore 下解得开，避免把坏值搬进数据库
            if (keyCrypto.decryptOrNull(legacy) == null) continue
            try {
                db.withTransaction {
                    providerKeyDao.upsert(
                        ProviderKeyEntity(configId, legacy, System.currentTimeMillis())
                    )
                }
            } catch (e: Exception) {
                continue // 未提交成功，保留旧密文，下次启动继续
            }
            // 提交后从数据库读回再验证一次，确认可用才清理旧密文
            val stored = providerKeyDao.byId(configId)?.ciphertext
            if (stored != null && keyCrypto.decryptOrNull(stored) != null) {
                keyCrypto.removeLegacyCiphertext(configId)
                moved++
            }
        }
        moved
    }

    private suspend fun migrateLegacyKey(configId: String, legacy: String) {
        if (keyCrypto.decryptOrNull(legacy) == null) return
        runCatching {
            db.withTransaction {
                providerKeyDao.upsert(
                    ProviderKeyEntity(configId, legacy, System.currentTimeMillis())
                )
            }
        }.onSuccess { keyCrypto.removeLegacyCiphertext(configId) }
    }
}

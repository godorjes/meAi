package app.polyglot.dev.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 模型服务配置（API Key 不落在数据库，单独加密保存） */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val providerType: String,      // kimi / deepseek / openai / glm / custom
    val apiType: String,           // openai_compatible
    val baseUrl: String,
    val modelIdsJson: String,      // JSON 数组字符串
    val selectedModelId: String,
    val testState: String,         // none / success / fail
    val testedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    /** 地址版本：地址变了就 +1，用来判断旧的查询结果还对不对应当前地址 */
    @ColumnInfo(defaultValue = "0") val endpointRevision: Long = 0,
    /** 凭证版本：Key 变了就 +1，旧凭证的查询结果不能算作当前凭证的证据 */
    @ColumnInfo(defaultValue = "0") val credentialRevision: Long = 0
)

@Entity(
    tableName = "sessions",
    indices = [Index("configId"), Index("updatedAt")]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val configId: String?,          // 配置被删除后为 null，仍可阅读历史
    val modelId: String,
    val modelNameSnapshot: String,
    val sourceSessionId: String?,
    val sourceTitle: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleteUntil: Long?          // 软删除截止时间（撤销窗口），null 表示正常
)

@Entity(
    tableName = "messages",
    indices = [Index("sessionId"), Index(value = ["sessionId", "seq"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,               // user / assistant
    val seq: Int,
    val content: String,
    val status: String,             // DONE / GENERATING / STOPPED / FAILED / INTERRUPTED
    val requestId: String?,
    val errorKind: String?,         // NO_NETWORK / TIMEOUT / INTERRUPTED_CONN / AUTH / QUOTA / RATE_LIMIT / MODEL / CONTEXT / UNAVAILABLE / UNKNOWN
    val createdAt: Long
)

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val sessionId: String,   // 空聊天页使用固定值 "new"
    val text: String,
    /**
     * 单调递增的写入版本。每次落盘在同一次事务里自增，
     * 用来确认某次写入是否仍然有效，避免旧内容回灌覆盖新输入。
     */
    @ColumnInfo(defaultValue = "0") val version: Long,
    val updatedAt: Long
)

/**
 * API Key 密文。与 providers 同一数据库、同一事务提交，
 * 保证配置与密钥要么一起生效、要么都不生效。
 * 明文 Key 不落库；加解密由 Android Keystore 中的 AES 密钥完成。
 */
@Entity(tableName = "provider_keys")
data class ProviderKeyEntity(
    @PrimaryKey val providerId: String,
    val ciphertext: String,
    val updatedAt: Long
)

/** 模型目录：公共参考资料（内置种子 + 我们维护的远程目录），与账号查询结果严格分开 */
@Entity(tableName = "catalog_models", primaryKeys = ["providerType", "modelId"])
data class CatalogModelEntity(
    val providerType: String,   // openai / glm / deepseek / kimi
    val modelId: String,
    val desc: String,
    val updatedAt: Long
)

/**
 * 当前配置的官方查询结果（按配置隔离）。
 *
 * 与公共目录分开存放：一个账号查不到某个模型，不能据此判定该模型在整个厂家范围下线，
 * 更不能拿它去删公共目录条目。
 * endpointRevision / credentialRevision 记录"这条结果是拿哪一版地址和凭证查到的"，
 * 换了地址或 Key 之后旧结果不再作为当前凭证的查询证据。
 */
@Entity(tableName = "config_model_snapshots", primaryKeys = ["configId", "modelId"])
data class ConfigModelSnapshotEntity(
    val configId: String,
    val modelId: String,
    val endpointRevision: Long,
    val credentialRevision: Long,
    val queriedAt: Long
)

/**
 * 每日定时任务拉取的各厂家最新模型缓存（每家前五）。
 * 用户打开模型选择器时只读这张表，不再每次在线请求。
 * 同步失败保留旧缓存，只有拉到非空列表才整家替换。
 */
@Entity(tableName = "app_model_lists", primaryKeys = ["providerType", "modelId"])
data class AppModelListEntity(
    val providerType: String,
    val modelId: String,
    val fetchedAt: Long
)

/**
 * App 级厂家 Key（用于每日拉取各厂家的模型列表）。
 * 明文只在内存中出现；入库前用 Keystore 加密，与用户配置的 Key 分开存放。
 */
@Entity(tableName = "app_provider_keys")
data class AppProviderKeyEntity(
    @PrimaryKey val providerType: String,
    val ciphertext: String,
    val updatedAt: Long
)

/**
 * 当前配置对某个模型的实际聊天测试结果。
 * 记录测试时的地址与凭证版本，保证"界面显示的上次测试成功"确实对应当前参数。
 */
@Entity(tableName = "model_verifications", primaryKeys = ["configId", "modelId"])
data class ModelVerificationEntity(
    val configId: String,
    val modelId: String,
    val endpointRevision: Long,
    val credentialRevision: Long,
    val success: Boolean,
    val errorKind: String?,
    val testedAt: Long
)

object MessageStatus {
    const val DONE = "DONE"
    const val GENERATING = "GENERATING"
    const val STOPPED = "STOPPED"
    const val FAILED = "FAILED"
    const val INTERRUPTED = "INTERRUPTED"

    /** 服务端未给出结束标记就断开：正文可能不完整，不能当作完成 */
    const val TRUNCATED = "TRUNCATED"

    /** 已交给模型且仍在跑的状态 */
    val UNFINISHED = setOf(GENERATING)
}

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

const val NEW_CHAT_DRAFT_ID = "new"

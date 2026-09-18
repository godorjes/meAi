package app.polyglot.dev.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun byId(id: String): ProviderEntity?

    @Query("SELECT COUNT(*) FROM providers")
    suspend fun count(): Int

    /** 自动命名去重用；只返回名称，不含密钥等敏感内容 */
    @Query("SELECT name FROM providers")
    suspend fun allNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE providers SET testState = :state, testedAt = :testedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTestState(id: String, state: String, testedAt: Long?, updatedAt: Long)

    @Query("UPDATE providers SET modelIdsJson = :modelIdsJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateModels(id: String, modelIdsJson: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM sessions WHERE configId = :configId AND deleteUntil IS NULL")
    suspend fun activeSessionCount(configId: String): Int
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE deleteUntil IS NULL ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE deleteUntil IS NOT NULL")
    suspend fun pendingDeleted(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET deleteUntil = :deleteUntil WHERE id = :id")
    suspend fun setDeleteUntil(id: String, deleteUntil: Long?)

    @Query("UPDATE sessions SET configId = :configId, modelId = :modelId, modelNameSnapshot = :snapshot, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateModel(id: String, configId: String?, modelId: String, snapshot: String, updatedAt: Long)

    @Query("UPDATE sessions SET configId = NULL WHERE configId = :configId")
    suspend fun detachConfig(configId: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM sessions WHERE deleteUntil IS NOT NULL AND deleteUntil < :now")
    suspend fun hardDeleteExpired(now: Long)

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun listBySession(sessionId: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE sessionId = :sessionId")
    suspend fun maxSeq(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Query("UPDATE messages SET content = :content, status = :status, errorKind = :errorKind WHERE id = :id")
    suspend fun updateContentStatus(id: String, content: String, status: String, errorKind: String?)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET status = 'INTERRUPTED' WHERE status = 'GENERATING'")
    suspend fun interruptAllGenerating(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT COUNT(*) FROM messages m INNER JOIN sessions s ON m.sessionId = s.id WHERE s.configId = :configId AND m.status = 'GENERATING'")
    suspend fun generatingCountForConfig(configId: String): Int
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE sessionId = :sessionId")
    suspend fun byId(sessionId: String): DraftEntity?

    @Query("SELECT text FROM drafts WHERE sessionId = :sessionId")
    suspend fun textOf(sessionId: String): String?

    @Query("SELECT version FROM drafts WHERE sessionId = :sessionId")
    suspend fun versionOf(sessionId: String): Long?

    @Query("SELECT sessionId, version FROM drafts")
    suspend fun allVersions(): List<DraftVersionRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DraftEntity)

    @Query("DELETE FROM drafts WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    /**
     * 清理没有归属会话的草稿。仅动 sessionId 不是空聊天页固定键、
     * 且在 sessions 里找不到对应记录的孤儿行，不碰任何在用草稿。
     */
    @Query("DELETE FROM drafts WHERE sessionId <> :newChatDraftId AND sessionId NOT IN (SELECT id FROM sessions)")
    suspend fun deleteOrphans(newChatDraftId: String): Int

    /**
     * 只清理仍停留在被发送版本上的草稿。
     * 发送期间用户新输入的内容版本更高，不会被这条语句删掉。
     */
    @Query("DELETE FROM drafts WHERE sessionId = :sessionId AND version = :version AND text = :text")
    suspend fun clearIfUnchanged(sessionId: String, version: Long, text: String): Int
}

data class DraftVersionRow(val sessionId: String, val version: Long)

@Dao
interface ProviderKeyDao {
    @Query("SELECT * FROM provider_keys WHERE providerId = :providerId")
    suspend fun byId(providerId: String): ProviderKeyEntity?

    @Query("SELECT * FROM provider_keys")
    suspend fun all(): List<ProviderKeyEntity>

    @Query("SELECT COUNT(*) FROM provider_keys WHERE providerId = :providerId")
    suspend fun count(providerId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderKeyEntity)

    @Query("DELETE FROM provider_keys WHERE providerId = :providerId")
    suspend fun delete(providerId: String)
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_models WHERE providerType = :providerType ORDER BY updatedAt DESC, modelId ASC")
    fun observeByProvider(providerType: String): Flow<List<CatalogModelEntity>>

    /** 公共目录整体很小，选择器按当前厂家过滤即可，不必按厂家多次查询 */
    @Query("SELECT * FROM catalog_models")
    fun observeAll(): Flow<List<CatalogModelEntity>>

    @Query("SELECT * FROM catalog_models WHERE providerType = :providerType")
    suspend fun listByProvider(providerType: String): List<CatalogModelEntity>

    @Query("SELECT COUNT(*) FROM catalog_models")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CatalogModelEntity>)
}

/** 某个配置自己的官方查询结果（与公共目录分开） */
@Dao
interface ConfigModelDao {
    @Query("SELECT * FROM config_model_snapshots WHERE configId = :configId")
    suspend fun listForConfig(configId: String): List<ConfigModelSnapshotEntity>

    @Query("SELECT * FROM config_model_snapshots WHERE configId = :configId")
    fun observeForConfig(configId: String): Flow<List<ConfigModelSnapshotEntity>>

    @Query("SELECT * FROM config_model_snapshots")
    suspend fun all(): List<ConfigModelSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ConfigModelSnapshotEntity>)

    /**
     * 用一次成功的查询结果替换该配置的证据：只清掉本次没有返回、且不是当前选中模型的旧条目，
     * 不清公共目录、不影响其他配置。
     */
    @Query(
        "DELETE FROM config_model_snapshots WHERE configId = :configId " +
            "AND modelId NOT IN (:keepIds) AND modelId <> :selectedModelId"
    )
    suspend fun pruneAfterQuery(configId: String, keepIds: List<String>, selectedModelId: String)

    @Query("DELETE FROM config_model_snapshots WHERE configId = :configId")
    suspend fun deleteForConfig(configId: String)
}

/** 某个配置对某个模型的实际聊天测试结果 */
@Dao
interface VerificationDao {
    @Query("SELECT * FROM model_verifications WHERE configId = :configId")
    suspend fun listForConfig(configId: String): List<ModelVerificationEntity>

    @Query("SELECT * FROM model_verifications WHERE configId = :configId AND modelId = :modelId")
    suspend fun byId(configId: String, modelId: String): ModelVerificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ModelVerificationEntity)

    @Query("DELETE FROM model_verifications WHERE configId = :configId")
    suspend fun deleteForConfig(configId: String)
}

/** 每日定时任务拉取的厂家模型缓存（每家前五） */
@Dao
interface AppModelListDao {
    /** 按写入顺序读回：厂家返回的顺序（GLM 实测从旧到新） */
    @Query("SELECT * FROM app_model_lists ORDER BY rowid ASC")
    fun observeAll(): Flow<List<AppModelListEntity>>

    @Query("SELECT * FROM app_model_lists WHERE providerType = :providerType")
    suspend fun listByProvider(providerType: String): List<AppModelListEntity>

    @Query("SELECT COUNT(*) FROM app_model_lists")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AppModelListEntity>)

    /** 拉到新列表后整家替换；没拉到内容时不要调用，保留旧缓存 */
    @Query("DELETE FROM app_model_lists WHERE providerType = :providerType")
    suspend fun deleteForProvider(providerType: String)
}

/** App 级厂家 Key（加密存储，与用户配置的 Key 分开） */
@Dao
interface AppProviderKeyDao {
    @Query("SELECT * FROM app_provider_keys WHERE providerType = :providerType")
    suspend fun byId(providerType: String): AppProviderKeyEntity?

    @Query("SELECT COUNT(*) FROM app_provider_keys")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppProviderKeyEntity)
}

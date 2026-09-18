package app.polyglot.dev.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 每日定时任务的模型列表拉取：用 App 级厂家 Key 请求各厂家的官方列表接口，
 * 取回后写入本地缓存（每家保留最新前五），用户打开选择器时不再在线请求。
 *
 * 端点按 2026-09-17 的接口文档与实测记录注册，不统一拼接 /models：
 * 千问百炼的原生目录需要业务空间 ID（{WorkspaceId} 专属域名），首版不请求，
 * 千问走预置目录（见 assets/model_catalog.json）。
 */
class ModelListClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 可拉取列表的厂家 → 完整列表地址 */
    val fetchableEndpoints: Map<String, String> = mapOf(
        "deepseek" to "https://api.deepseek.com/models",
        "glm" to "https://open.bigmodel.cn/api/paas/v4/models",
        "kimi" to "https://api.moonshot.cn/v1/models"
    )

    /** 每个厂家缓存的最新模型数量 */
    val cacheLimit = 5

    /**
     * 请求模型列表并返回最新前 [cacheLimit] 个模型 ID。
     * 空响应、格式不对、网络失败都抛异常，由调用方决定是否保留旧缓存。
     */
    suspend fun fetchTopModels(providerType: String, apiKey: String): List<String> {
        val url = fetchableEndpoints[providerType]
            ?: throw IllegalArgumentException("厂家不支持拉取模型列表：$providerType")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        val body = suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            if (!cont.isCancelled) {
                                cont.resumeWith(Result.failure(IOException("HTTP ${it.code}")))
                            }
                            return
                        }
                        val text = it.body?.string().orEmpty()
                        if (!cont.isCancelled) cont.resumeWith(Result.success(text))
                    }
                }
            })
        }

        val ids = parseIds(body)
        // 按厂家的 created 时间戳取最新前五；没有时间戳（如 DeepSeek）保持返回顺序
        return ids.take(cacheLimit)
    }

    /**
     * 解析 `data[].id` 并按 `created` 倒序排列（最新在前）。
     * 没有 created 字段的按 0 处理，稳定排序保持原顺序；纯函数，便于直接测试。
     */
    internal fun parseIds(body: String): List<String> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IOException("响应不是有效 JSON")
        val data = root.optJSONArray("data") ?: throw IOException("响应缺少 data 数组")
        data class Row(val id: String, val created: Long)
        val rows = buildList {
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isNotBlank()) add(Row(id, obj.optLong("created", 0L)))
            }
        }
        return rows.sortedByDescending { it.created }.map { it.id }
    }
}

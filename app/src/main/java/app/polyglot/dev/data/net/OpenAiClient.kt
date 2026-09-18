package app.polyglot.dev.data.net

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.trySendBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 错误分类，用于界面的可操作提示（对应规格第 6 节） */
enum class ErrorKind {
    NO_NETWORK,      // 明确无网络
    TIMEOUT,         // 连接超时
    INTERRUPTED_CONN,// 连接中断
    AUTH,            // 凭证无效
    QUOTA,           // 额度不足
    RATE_LIMIT,      // 限流
    MODEL,           // 模型不存在或不支持
    CONTEXT,         // 上下文超限
    UNAVAILABLE,     // 服务暂时不可用
    PROTOCOL,        // HTTP 成功但响应不是有效聊天结果
    UNKNOWN
}

class ApiException(val kind: ErrorKind, message: String? = null) : IOException(message ?: kind.name)

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent

    /** 协议明确结束（收到 [DONE] 或 finish_reason） */
    data object Done : StreamEvent

    /** 连接在有结束标记前就断了：正文可能不完整，不能当作完成 */
    data class Truncated(val detail: String?) : StreamEvent

    data class Failure(val kind: ErrorKind, val detail: String?) : StreamEvent
}

data class ChatMessage(val role: String, val content: String)

/**
 * 公共模型目录的下载器。
 * 只请求固定、可信的 HTTPS 地址，**不携带用户 API Key、聊天正文或配置明细**；
 * 目录地址由维护端确定，本轮尚未部署，因此未配置时整个流程直接跳过。
 */
class CatalogFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 返回目录 JSON 原文；失败或地址未配置时返回 null，调用方保留现有目录 */
    suspend fun fetch(url: String): String? {
        if (url.isBlank() || !url.startsWith("https://")) return null
        val request = Request.Builder().url(url).get().build()
        return suspendCancellableCoroutine { cont: CancellableContinuation<String?> ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = runCatching {
                        response.use { if (it.isSuccessful) it.body?.string() else null }
                    }.getOrNull()
                    if (!cont.isCancelled) cont.resume(body)
                }
            })
        }
    }
}

/**
 * 厂家的模型列表查询能力。
 * 官方有接口不等于当前网络可达，也不能在运行时猜多个域名轮流试探；
 * 未核实的厂家干脆不去请求，直接用公共参考目录，避免把用户 Key 发到没核实过的地址。
 */
enum class ModelQuerySupport {
    /** 官方文档提供可用的模型列表接口 */
    SUPPORTED,

    /** 尚未核实本 App 接入方式下可靠的通用模型列表接口 */
    UNVERIFIED,

    /** 自定义兼容服务：能查就查，不支持就用配置缓存或手动填写 */
    USER_DEFINED
}

/**
 * 对话服务客户端。抽成接口是为了能用可控的假实现驱动生成流程，
 * 直接断言"这次请求到底用了哪个服务地址、哪个模型"，
 * 而不是只看界面上的名字有没有变。
 */
interface ChatStreamClient {
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String>
    suspend fun testChat(baseUrl: String, apiKey: String, modelId: String)
    fun streamChat(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ChatMessage>
    ): Flow<StreamEvent>
}

/**
 * OpenAI 兼容协议的极简客户端：仅覆盖首版所需的
 * GET /models、POST /chat/completions（流式与非流式最小请求）。
 *
 * 所有请求都通过 OkHttp 的异步接口派发，协程取消会真正中止底层调用，
 * 不会出现“界面已经放弃、请求还在后台跑”的情况。
 */
class OpenAiClient : ChatStreamClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 流式读取不设整体超时，由用户停止或系统中断收尾
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 列表／查询类请求用独立的有限超时。
     * 目录查询不能拖住页面：连接 5 秒、读写各 10 秒，比聊天请求短得多。
     */
    private val queryClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun normalizeBase(baseUrl: String): String =
        baseUrl.trim().removeSuffix("/")

    /** 拉取模型列表；失败时抛 ApiException，界面允许改用手动填写 */
    override suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val request = Request.Builder()
            .url("${normalizeBase(baseUrl)}/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return await(queryClient, request) { resp ->
            val body = resp.body?.string().orEmpty()
            val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }
        }
    }

    /**
     * 测试连接：发送最小文本请求，并且校验响应确实是有效的聊天结果。
     * 只看 HTTP 2xx 会把“网关返回的登录页”“错误的兼容地址”当成成功。
     * 可能产生少量用量。取消会中止底层请求。
     */
    override suspend fun testChat(baseUrl: String, apiKey: String, modelId: String) {
        val payload = JSONObject()
            .put("model", modelId)
            .put("max_tokens", 1)
            .put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        val request = Request.Builder()
            .url("${normalizeBase(baseUrl)}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        await(client, request) { resp ->
            val body = resp.body?.string().orEmpty()
            requireChatCompletion(body)
        }
    }

    /** HTTP 成功但内容不是聊天结果时抛出，避免把无效服务当成“连接成功” */
    private fun requireChatCompletion(body: String) {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw ApiException(ErrorKind.PROTOCOL, "响应不是有效的 JSON")
        }
        if (json.has("error")) {
            val msg = json.optJSONObject("error")?.optString("message").orEmpty()
            throw ApiException(ErrorKind.UNKNOWN, msg.ifBlank { "服务返回错误" })
        }
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw ApiException(ErrorKind.PROTOCOL, "响应没有 choices 字段")
        }
        val first = choices.optJSONObject(0)
        if (first == null || (!first.has("message") && !first.has("delta"))) {
            throw ApiException(ErrorKind.PROTOCOL, "响应缺少回复内容")
        }
    }

    /**
     * 流式对话：以 SSE 分段返回 Delta。
     *
     * 通道写满时读取线程会阻塞（[trySendBlocking]），把下游落盘速度传导回来形成背压，
     * 不再像 trySend 那样在缓冲区满时静默丢弃片段。
     * 收到 [DONE] 或 finish_reason 才算正常结束，否则报告 Truncated。
     */
    override fun streamChat(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ChatMessage>
    ): Flow<StreamEvent> = callbackFlow {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val payload = JSONObject()
            .put("model", modelId)
            .put("stream", true)
            .put("messages", arr)

        val request = Request.Builder()
            .url("${normalizeBase(baseUrl)}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val err = networkError(e)
                trySendBlocking(StreamEvent.Failure(err.kind, err.message))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val err = httpError(resp)
                        trySendBlocking(StreamEvent.Failure(err.kind, err.message))
                        close()
                        return
                    }
                    var terminal = false
                    try {
                        val body = resp.body ?: throw ApiException(ErrorKind.UNKNOWN, "空响应")
                        val source = body.source()
                        loop@ while (true) {
                            val line = source.readUtf8Line() ?: break@loop
                            if (!line.startsWith("data:")) continue@loop
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") {
                                terminal = true
                                break@loop
                            }
                            if (data.isEmpty()) continue@loop
                            val parsed = parseChunk(data)
                            if (parsed.finishReason != null) terminal = true
                            if (!parsed.content.isNullOrEmpty()) {
                                trySendBlocking(StreamEvent.Delta(parsed.content))
                            }
                        }
                    } catch (e: IOException) {
                        val err = networkError(e)
                        trySendBlocking(StreamEvent.Failure(err.kind, err.message))
                        close()
                        return
                    } catch (e: Exception) {
                        trySendBlocking(StreamEvent.Failure(ErrorKind.UNKNOWN, e.message))
                        close()
                        return
                    }
                    // 服务端没给结束标记就断开：正文可能被截断，交给上层标记为未完成
                    trySendBlocking(
                        if (terminal) StreamEvent.Done else StreamEvent.Truncated("服务未返回结束标记")
                    )
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }

    internal class Chunk(val content: String?, val finishReason: String?)

    /**
     * 解析一段 SSE 增量。
     *
     * 关键点：`org.json` 的 `optString(name, fallback)` 在字段值是**显式 null** 时
     * 返回字符串 "null"（因为 JSONObject.NULL.toString() 就是 "null"），
     * 而不是返回 fallback。DeepSeek 等兼容服务会在增量里发 `"content": null`
     * （例如只带 role 的首段、只带 finish_reason 的末段），
     * 直接用 optString 会把这些 null 当成正文追加，界面上就会出现一串 null。
     * 所以这里先用 isNull/是否缺少字段判断，再取值。
     */
    internal fun parseChunk(data: String): Chunk = runCatching {
        val root = JSONObject(data)
        val choices = root.optJSONArray("choices") ?: return Chunk(null, null)
        if (choices.length() == 0) return Chunk(null, null)
        val first = choices.optJSONObject(0) ?: return Chunk(null, null)

        val delta = first.optJSONObject("delta")
        val text = when {
            delta == null -> null
            delta.isNull("content") -> null
            else -> delta.optString("content", null)
        }

        val finish = when {
            first.isNull("finish_reason") -> null
            else -> first.optString("finish_reason", null)
        }
        Chunk(text?.takeIf { it.isNotEmpty() }, finish?.takeIf { it.isNotBlank() && it != "null" })
    }.getOrElse { Chunk(null, null) }

    // ---------- 请求派发 ----------

    /** 派发请求并把取消传导到 OkHttp，避免协程取消后请求仍在后台运行 */
    private suspend fun <T> await(
        http: OkHttpClient, request: Request, parse: (Response) -> T
    ): T =
        suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(networkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                        return
                    }
                    try {
                        response.use { resp ->
                            if (!resp.isSuccessful) throw httpError(resp)
                            cont.resume(parse(resp))
                        }
                    } catch (e: Throwable) {
                        cont.resumeWithException(if (e is IOException && e !is ApiException) networkError(e) else e)
                    }
                }
            })
        }

    private fun httpError(resp: Response): ApiException {
        val bodyText = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        val serverMsg = runCatching {
            JSONObject(bodyText).optJSONObject("error")?.optString("message")
        }.getOrNull()
        val lowered = (serverMsg ?: bodyText).lowercase()
        val kind = when {
            resp.code == 401 || resp.code == 403 -> ErrorKind.AUTH
            resp.code == 402 -> ErrorKind.QUOTA
            resp.code == 429 -> ErrorKind.RATE_LIMIT
            resp.code == 404 -> ErrorKind.MODEL
            resp.code == 413 -> ErrorKind.CONTEXT
            resp.code == 400 && (lowered.contains("context") || lowered.contains("token") && lowered.contains("length")) -> ErrorKind.CONTEXT
            resp.code == 400 && lowered.contains("model") -> ErrorKind.MODEL
            resp.code in 500..599 -> ErrorKind.UNAVAILABLE
            lowered.contains("insufficient") || lowered.contains("quota") || lowered.contains("balance") -> ErrorKind.QUOTA
            lowered.contains("rate limit") -> ErrorKind.RATE_LIMIT
            else -> ErrorKind.UNKNOWN
        }
        return ApiException(kind, serverMsg ?: "HTTP ${resp.code}")
    }

    private fun networkError(e: IOException): ApiException = when (e) {
        is ApiException -> e
        is SocketTimeoutException -> ApiException(ErrorKind.TIMEOUT, e.message)
        is UnknownHostException, is ConnectException -> ApiException(ErrorKind.NO_NETWORK, e.message)
        else -> ApiException(ErrorKind.INTERRUPTED_CONN, e.message)
    }
}

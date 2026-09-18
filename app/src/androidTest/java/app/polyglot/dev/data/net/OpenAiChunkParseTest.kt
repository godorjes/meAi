package app.polyglot.dev.data.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 流式增量解析：重点是**显式 null 不能被当成正文**。
 *
 * 起因：`org.json` 的 `optString(name, fallback)` 在字段值是显式 null 时返回字符串 "null"
 * （JSONObject.NULL.toString() 就是 "null"），不会返回 fallback。
 * DeepSeek 等兼容服务会在增量里发 `"content": null`，于是界面上出现一串 null。
 * 这里用真实出现过的报文形状锁定行为。
 */
@RunWith(AndroidJUnit4::class)
class OpenAiChunkParseTest {

    private val client = OpenAiClient()

    @Test
    fun content为显式null时不产生正文() {
        // DeepSeek 只带 role 或只带 finish_reason 的增量会这样发
        assertEquals(
            null,
            client.parseChunk("""{"choices":[{"delta":{"content":null},"finish_reason":null}]}""").content
        )
        assertEquals(
            null,
            client.parseChunk("""{"choices":[{"delta":{"role":"assistant","content":null}}]}""").content
        )
        assertEquals(
            null,
            client.parseChunk("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""").content
        )
    }

    @Test
    fun 正常正文照常取出() {
        assertEquals(
            "你好",
            client.parseChunk("""{"choices":[{"delta":{"content":"你好"}}]}""").content
        )
    }

    @Test
    fun 空字符串正文当作没有正文() {
        assertEquals(
            null,
            client.parseChunk("""{"choices":[{"delta":{"content":""}}]}""").content
        )
    }

    /** 模型真的输出 "null" 这个词时必须保留，不能一律过滤掉 */
    @Test
    fun 正文本身就是null这个词时要保留() {
        assertEquals(
            "null",
            client.parseChunk("""{"choices":[{"delta":{"content":"null"}}]}""").content
        )
    }

    @Test
    fun 结束标记只在真的出现时返回() {
        assertEquals(
            "stop",
            client.parseChunk("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""").finishReason
        )
        assertNull(
            client.parseChunk("""{"choices":[{"delta":{"content":"x"},"finish_reason":null}]}""").finishReason
        )
    }

    @Test
    fun 异常报文不抛异常也不产生正文() {
        assertNull(client.parseChunk("").content)
        assertNull(client.parseChunk("not json").content)
        assertNull(client.parseChunk("""{"choices":[]}""").content)
        assertNull(client.parseChunk("""{"choices":[{"delta":{"content":"x"}}]}""").finishReason)
    }
}

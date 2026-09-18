package app.polyglot.dev.data.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * 模型列表解析：按 created 倒序取最新，没有时间戳时保持原顺序。
 * 纯函数，不联网。
 */
@RunWith(AndroidJUnit4::class)
class ModelListClientTest {

    private val client = ModelListClient()

    @Test
    fun 按created倒序取最新() {
        val body = """
            {"object":"list","data":[
              {"id":"m-old","created":100},
              {"id":"m-new","created":300},
              {"id":"m-mid","created":200}
            ]}
        """.trimIndent()
        assertEquals(listOf("m-new", "m-mid", "m-old"), client.parseIds(body))
    }

    @Test
    fun 没有时间戳时保持原顺序() {
        val body = """
            {"object":"list","data":[
              {"id":"a"},{"id":"b"}
            ]}
        """.trimIndent()
        assertEquals(listOf("a", "b"), client.parseIds(body))
    }

    @Test
    fun 相同时间戳稳定保持原顺序() {
        val body = """
            {"object":"list","data":[
              {"id":"x","created":5},
              {"id":"y","created":5},
              {"id":"z","created":5}
            ]}
        """.trimIndent()
        assertEquals(listOf("x", "y", "z"), client.parseIds(body))
    }

    @Test
    fun 空id与无效内容被拒绝或跳过() {
        assertThrows(IOException::class.java) { client.parseIds("not json") }
        assertThrows(IOException::class.java) { client.parseIds("""{"object":"list"}""") }
        assertEquals(
            listOf("ok"),
            client.parseIds("""{"object":"list","data":[{"id":""},{"id":"ok"}]}""")
        )
    }
}

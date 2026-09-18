package app.polyglot.dev.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 公共目录的解析与校验。
 *
 * 规格要求：对格式、版本、大小、重复 ID、支持的厂家和字段进行校验，
 * 校验通过才原子替换；损坏、未知 schema、重复条目或过期版本都不能覆盖已经有效的目录。
 * 这里是纯函数，不需要真的联网。
 */
@RunWith(AndroidJUnit4::class)
class PublicCatalogTest {

    private val valid = """
        {"schemaVersion":1,"catalogVersion":5,"publishedAt":123,
         "providers":{"deepseek":[{"id":"deepseek-flash","desc":""}]}}
    """.trimIndent()

    @Test
    fun 合法目录可以解析() {
        val parsed = PublicCatalog.parse(valid, currentVersion = 4)
        assertNotNull(parsed)
        assertEquals(5L, parsed!!.catalogVersion)
        assertEquals(listOf("deepseek-flash"), parsed.entries.map { it.modelId })
    }

    @Test
    fun 损坏的目录被拒绝() {
        assertNull(PublicCatalog.parse("", 0))
        assertNull(PublicCatalog.parse("not json", 0))
        assertNull(PublicCatalog.parse("{}", 0))
        assertNull(PublicCatalog.parse("""{"schemaVersion":1,"catalogVersion":5}""", 0))
        assertNull(
            "providers 不是对象时必须拒绝",
            PublicCatalog.parse("""{"schemaVersion":1,"catalogVersion":5,"providers":[]}""", 0)
        )
    }

    @Test
    fun 未知schema版本被拒绝() {
        val raw = """{"schemaVersion":99,"catalogVersion":5,"providers":{"deepseek":[{"id":"m"}]}}"""
        assertNull(PublicCatalog.parse(raw, 0))
    }

    @Test
    fun 过期或不新的版本不能覆盖现有目录() {
        assertNull("相同版本不替换", PublicCatalog.parse(valid, currentVersion = 5))
        assertNull("更旧版本不替换", PublicCatalog.parse(valid, currentVersion = 9))
        assertNull(
            "版本号缺失不替换",
            PublicCatalog.parse("""{"schemaVersion":1,"providers":{"deepseek":[{"id":"m"}]}}""", 0)
        )
    }

    @Test
    fun 重复条目被拒绝() {
        val raw = """
            {"schemaVersion":1,"catalogVersion":5,
             "providers":{"deepseek":[{"id":"m"},{"id":"m"}]}}
        """.trimIndent()
        assertNull("同一厂家下重复 ID 视为目录损坏", PublicCatalog.parse(raw, 0))

        val crossProvider = """
            {"schemaVersion":1,"catalogVersion":5,
             "providers":{"deepseek":[{"id":"m"}],"kimi":[{"id":"m"}]}}
        """.trimIndent()
        assertNotNull("不同厂家同名模型是合法的", PublicCatalog.parse(crossProvider, 0))
    }

    @Test
    fun 未知厂家与空模型ID被拒绝() {
        assertNull(
            "未知厂家一律拒绝，避免目录数据凭空引入新来源",
            PublicCatalog.parse(
                """{"schemaVersion":1,"catalogVersion":5,"providers":{"evil":[{"id":"m"}]}}""", 0
            )
        )
        assertNull(
            PublicCatalog.parse(
                """{"schemaVersion":1,"catalogVersion":5,"providers":{"deepseek":[{"id":"   "}]}}""", 0
            )
        )
    }

    @Test
    fun 超大目录被拒绝() {
        val huge = "{" + "\"x\":\"" + "a".repeat(300 * 1024) + "\"}"
        assertNull(PublicCatalog.parse(huge, 0))
    }

    /** 随包内置的目录必须能被自己的校验规则接受，否则首次安装会没有目录可用 */
    @Test
    fun 内置目录本身通过校验() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val raw = ctx.assets.open("model_catalog.json").bufferedReader().use { it.readText() }
        val parsed = PublicCatalog.parse(raw, currentVersion = 0)
        assertNotNull("内置目录必须合法", parsed)
        assertTrue("内置目录不能为空", parsed!!.entries.isNotEmpty())
        // 不编造能力描述：内置条目的描述一律为空
        assertTrue(
            "内置目录不应带未经核实的能力描述",
            parsed.entries.all { it.desc.isBlank() }
        )
    }
}

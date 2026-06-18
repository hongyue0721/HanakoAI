package `fun`.kirari.hanako.network.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSearchJudgeParsingTest {

    // === JSON 格式测试 ===

    @Test
    fun `parse JSON need_search true with keywords returns shouldSearch true`() {
        val decision = LlmSearchJudge.parseResponse("""{"need_search": true, "keywords": "2024 奥运会 举办地"}""")
        assertTrue(decision.shouldSearch)
        assertEquals("2024 奥运会 举办地", decision.keywords)
        assertFalse(decision.failed)
    }

    @Test
    fun `parse JSON need_search false returns shouldSearch false`() {
        val decision = LlmSearchJudge.parseResponse("""{"need_search": false, "keywords": ""}""")
        assertFalse(decision.shouldSearch)
        assertNull(decision.keywords)
        assertFalse(decision.failed)
    }

    @Test
    fun `parse JSON with spaces in keys and values`() {
        val decision = LlmSearchJudge.parseResponse("""{ "need_search" : true , "keywords" : "latest AI models 2024" }""")
        assertTrue(decision.shouldSearch)
        assertEquals("latest AI models 2024", decision.keywords)
    }

    @Test
    fun `parse JSON need_search true but empty keywords returns failed`() {
        val decision = LlmSearchJudge.parseResponse("""{"need_search": true, "keywords": ""}""")
        assertFalse(decision.shouldSearch)
        assertTrue(decision.failed)
    }

    @Test
    fun `parse JSON with escaped quotes in keywords`() {
        val decision = LlmSearchJudge.parseResponse("""{"need_search": true, "keywords": "智谱 \"GLM-4\" 模型"}""")
        assertTrue(decision.shouldSearch)
        assertEquals("智谱 \"GLM-4\" 模型", decision.keywords)
    }

    @Test
    fun `parse JSON with extra text around it`() {
        val decision = LlmSearchJudge.parseResponse("""好的，分析结果如下：
            {"need_search": true, "keywords": "二十届四中全会精神 求是杂志"}
            以上是判断结果。""")
        assertTrue(decision.shouldSearch)
        assertEquals("二十届四中全会精神 求是杂志", decision.keywords)
    }

    @Test
    fun `parse JSON without quotes around boolean`() {
        val decision = LlmSearchJudge.parseResponse("""{"need_search":false,"keywords":""}""")
        assertFalse(decision.shouldSearch)
    }

    @Test
    fun `parse JSON with Chinese keywords containing special chars`() {
        val decision = LlmSearchJudge.parseResponse("""{"need_search": true, "keywords": "2026年1月 求是杂志 习近平"}""")
        assertTrue(decision.shouldSearch)
        assertEquals("2026年1月 求是杂志 习近平", decision.keywords)
    }

    // === 降级：SEARCH/NOSEARCH 文本格式测试 ===

    @Test
    fun `parse SEARCH with keywords falls back to text format`() {
        val decision = LlmSearchJudge.parseResponse("SEARCH\n2024 奥运会 举办地")
        assertTrue(decision.shouldSearch)
        assertEquals("2024 奥运会 举办地", decision.keywords)
        assertFalse(decision.failed)
    }

    @Test
    fun `parse NOSEARCH falls back to text format`() {
        val decision = LlmSearchJudge.parseResponse("NOSEARCH")
        assertFalse(decision.shouldSearch)
        assertNull(decision.keywords)
        assertFalse(decision.failed)
    }

    @Test
    fun `parse lowercase search falls back to text format`() {
        val decision = LlmSearchJudge.parseResponse("search\nlatest news today")
        assertTrue(decision.shouldSearch)
        assertEquals("latest news today", decision.keywords)
    }

    @Test
    fun `parse SEARCH without keywords returns failed true`() {
        val decision = LlmSearchJudge.parseResponse("SEARCH\n")
        assertFalse(decision.shouldSearch)
        assertTrue(decision.failed)
    }

    // === 边界情况 ===

    @Test
    fun `parse empty response returns shouldSearch false`() {
        val decision = LlmSearchJudge.parseResponse("")
        assertFalse(decision.shouldSearch)
        assertFalse(decision.failed)
    }

    @Test
    fun `parse random text returns shouldSearch false`() {
        val decision = LlmSearchJudge.parseResponse("I think this question is about math.")
        assertFalse(decision.shouldSearch)
        assertFalse(decision.failed)
    }

    @Test
    fun `parse SEARCH with extra whitespace around keywords trims correctly`() {
        val decision = LlmSearchJudge.parseResponse("SEARCH\n  2024 Olympics host city  ")
        assertTrue(decision.shouldSearch)
        assertEquals("2024 Olympics host city", decision.keywords)
    }

    @Test
    fun `parse SEARCH with keywords on third line picks first non-blank`() {
        val decision = LlmSearchJudge.parseResponse("SEARCH\n\n2024 election results")
        assertTrue(decision.shouldSearch)
        assertEquals("2024 election results", decision.keywords)
    }
}

package `fun`.kirari.hanako.network.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LlmSearchJudge] 的 JSON 解析逻辑单元测试。
 *
 * 不依赖网络，只测试 [LlmSearchJudge.parseResponse] 对各种 LLM 输出格式的解析。
 */
class LlmSearchJudgeTest {

    // ---- JSON 格式 ----

    @Test
    fun parseResponse_jsonTrueWithKeywords_returnsShouldSearch() {
        val response = """{"need_search": true, "keywords": "2024 诺贝尔奖"}"""
        val decision = LlmSearchJudge.parseResponse(response)
        assertTrue(decision.shouldSearch)
        assertEquals("2024 诺贝尔奖", decision.keywords)
        assertFalse(decision.failed)
    }

    @Test
    fun parseResponse_jsonFalse_returnsNoSearch() {
        val response = """{"need_search": false, "keywords": ""}"""
        val decision = LlmSearchJudge.parseResponse(response)
        assertFalse(decision.shouldSearch)
        assertNull(decision.keywords)
        assertFalse(decision.failed)
    }

    @Test
    fun parseResponse_jsonTrueButKeywordsBlank_returnsFailed() {
        val response = """{"need_search": true, "keywords": ""}"""
        val decision = LlmSearchJudge.parseResponse(response)
        assertFalse(decision.shouldSearch)
        assertNull(decision.keywords)
        assertTrue(decision.failed)
    }

    @Test
    fun parseResponse_jsonTrueNoKeywordsField_returnsFailed() {
        val response = """{"need_search": true}"""
        val decision = LlmSearchJudge.parseResponse(response)
        assertFalse(decision.shouldSearch)
        assertNull(decision.keywords)
        assertTrue(decision.failed)
    }

    @Test
    fun parseResponse_jsonWithExtraFields_stillParses() {
        val response = """{"need_search": true, "keywords": "test", "reason": "time-sensitive"}"""
        val decision = LlmSearchJudge.parseResponse(response)
        assertTrue(decision.shouldSearch)
        assertEquals("test", decision.keywords)
    }

    @Test
    fun parseResponse_jsonCaseInsensitive_parses() {
        val response = """{"NEED_SEARCH": TRUE, "KEYWORDS": "test"}"""
        val decision = LlmSearchJudge.parseResponse(response)
        assertTrue(decision.shouldSearch)
        assertEquals("test", decision.keywords)
    }

    // ---- 文本格式（SEARCH 前缀） ----

    @Test
    fun parseResponse_searchPrefixWithKeywords_returnsShouldSearch() {
        val response = "SEARCH\n2024 巴黎奥运"
        val decision = LlmSearchJudge.parseResponse(response)
        assertTrue(decision.shouldSearch)
        assertEquals("2024 巴黎奥运", decision.keywords)
    }

    @Test
    fun parseResponse_searchPrefixNoKeywords_returnsFailed() {
        val response = "SEARCH"
        val decision = LlmSearchJudge.parseResponse(response)
        assertFalse(decision.shouldSearch)
        assertTrue(decision.failed)
    }

    @Test
    fun parseResponse_searchPrefixCaseInsensitive_parses() {
        val response = "search\nclimate change 2024"
        val decision = LlmSearchJudge.parseResponse(response)
        assertTrue(decision.shouldSearch)
        assertEquals("climate change 2024", decision.keywords)
    }

    // ---- 无搜索 ----

    @Test
    fun parseResponse_plainText_returnsNoSearch() {
        val response = "这是一道数学题，不需要搜索。"
        val decision = LlmSearchJudge.parseResponse(response)
        assertFalse(decision.shouldSearch)
        assertNull(decision.keywords)
        assertFalse(decision.failed)
    }

    @Test
    fun parseResponse_emptyString_returnsNoSearch() {
        val decision = LlmSearchJudge.parseResponse("")
        assertFalse(decision.shouldSearch)
        assertFalse(decision.failed)
    }

    @Test
    fun parseResponse_blankString_returnsNoSearch() {
        val decision = LlmSearchJudge.parseResponse("   ")
        assertFalse(decision.shouldSearch)
        assertFalse(decision.failed)
    }

    // ---- JSON 变体格式 ----

    @Test
    fun parseResponse_jsonWithColonNoQuotes_parses() {
        val response = """need_search: true, keywords: "人工智能 最新进展""""
        val decision = LlmSearchJudge.parseResponse(response)
        assertTrue(decision.shouldSearch)
        assertEquals("人工智能 最新进展", decision.keywords)
    }

    @Test
    fun parseResponse_jsonWithEscapedQuotes_parses() {
        val response = """{"need_search": true, "keywords": "量子计算 \"突破\""}"""
        val decision = LlmSearchJudge.parseResponse(response)
        assertTrue(decision.shouldSearch)
        assertEquals("""量子计算 "突破"""", decision.keywords)
    }
}

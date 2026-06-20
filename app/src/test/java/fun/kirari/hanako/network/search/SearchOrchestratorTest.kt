package `fun`.kirari.hanako.network.search

import `fun`.kirari.hanako.data.SearchProviderConfig
import `fun`.kirari.hanako.data.SearchProviderKind
import `fun`.kirari.hanako.data.WebSearchSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchOrchestrator] 单元测试。
 *
 * 使用 stub [SearchClient] 和 [SearchJudge]，验证编排流程：
 * - 前置检查（开关、API Key、API URL）
 * - LLM 判断 → 搜索 → 格式化
 * - 各种跳过和失败场景
 */
class SearchOrchestratorTest {

    private val testSearchProvider = SearchProviderConfig(
        kind = SearchProviderKind.TAVILY,
        baseUrl = "https://api.tavily.com/search",
        apiKey = "test-api-key"
    )

    private val testProvider = `fun`.kirari.hanako.data.ModelProviderConfig(
        baseUrl = "https://example.com",
        apiKey = "test-key"
    )

    private fun buildContext(
        settings: WebSearchSettings,
        isAutomation: Boolean = false,
        skipDefaultProvider: Boolean = false
    ): SearchContext {
        // 默认确保 provider 有 apiKey 和 baseUrl，前置检查能通过
        // skipDefaultProvider = true 时保留原始 settings（用于测试空 key/URL 场景）
        val effectiveSettings = if (skipDefaultProvider) {
            settings
        } else if (settings.provider.apiKey.isBlank() || settings.provider.baseUrl.isBlank()) {
            settings.copy(provider = testSearchProvider)
        } else {
            settings
        }
        return SearchContext(
            questionText = "2024年诺贝尔奖获得者是谁？",
            settings = effectiveSettings,
            llmProvider = testProvider,
            llmModel = "test-model",
            trustAllHttps = false,
            isAutomation = isAutomation
        )
    }

    // ---- 前置检查 ----

    @Test
    fun execute_disabled_returnsSkipDisabled() = runBlocking {
        val orchestrator = SearchOrchestrator(StubSearchClient(), StubSearchJudge())
        val settings = WebSearchSettings(enabled = false)

        val outcome = orchestrator.execute(buildContext(settings))

        assertFalse(outcome.performed)
        assertEquals(SearchSkipReason.DISABLED, outcome.skipReason)
        assertNull(outcome.formattedText)
    }

    @Test
    fun execute_automationDisabled_returnsSkipAutomationDisabled() = runBlocking {
        val orchestrator = SearchOrchestrator(StubSearchClient(), StubSearchJudge())
        val settings = WebSearchSettings(enabled = true, automationAlsoSearch = false)

        val outcome = orchestrator.execute(buildContext(settings, isAutomation = true))

        assertFalse(outcome.performed)
        assertEquals(SearchSkipReason.AUTOMATION_DISABLED, outcome.skipReason)
    }

    @Test
    fun execute_automationEnabledWithFlag_proceedsToSearch() = runBlocking {
        val orchestrator = SearchOrchestrator(
            StubSearchClient(SearchResponse(listOf(SearchHit("Title", "Content")))),
            StubSearchJudge(SearchDecision(shouldSearch = true, keywords = "test"))
        )
        val settings = WebSearchSettings(enabled = true, automationAlsoSearch = true)

        val outcome = orchestrator.execute(buildContext(settings, isAutomation = true))

        assertTrue(outcome.performed)
        assertEquals(1, outcome.results.size)
        assertEquals("test", outcome.keywords)
    }

    @Test
    fun execute_blankApiKey_returnsSkipApiKeyMissing() = runBlocking {
        val orchestrator = SearchOrchestrator(StubSearchClient(), StubSearchJudge())
        val settings = WebSearchSettings(
            enabled = true,
            provider = SearchProviderConfig(apiKey = "")
        )

        val outcome = orchestrator.execute(buildContext(settings, skipDefaultProvider = true))

        assertEquals(SearchSkipReason.API_KEY_MISSING, outcome.skipReason)
    }

    @Test
    fun execute_blankBaseUrl_returnsSkipApiUrlMissing() = runBlocking {
        val orchestrator = SearchOrchestrator(StubSearchClient(), StubSearchJudge())
        val settings = WebSearchSettings(
            enabled = true,
            provider = SearchProviderConfig(apiKey = "key", baseUrl = "")
        )

        val outcome = orchestrator.execute(buildContext(settings, skipDefaultProvider = true))

        assertEquals(SearchSkipReason.API_URL_MISSING, outcome.skipReason)
    }

    // ---- LLM 判断 ----

    @Test
    fun execute_judgeFailed_returnsSkipLlmJudgeFailed() = runBlocking {
        val orchestrator = SearchOrchestrator(
            StubSearchClient(),
            StubSearchJudge(SearchDecision(shouldSearch = false, failed = true))
        )
        val settings = WebSearchSettings(enabled = true)

        val outcome = orchestrator.execute(buildContext(settings))

        assertEquals(SearchSkipReason.LLM_JUDGE_FAILED, outcome.skipReason)
    }

    @Test
    fun execute_judgeNoSearch_returnsSkipLlmJudgeNoSearch() = runBlocking {
        val orchestrator = SearchOrchestrator(
            StubSearchClient(),
            StubSearchJudge(SearchDecision(shouldSearch = false))
        )
        val settings = WebSearchSettings(enabled = true)

        val outcome = orchestrator.execute(buildContext(settings))

        assertEquals(SearchSkipReason.LLM_JUDGE_NO_SEARCH, outcome.skipReason)
    }

    @Test
    fun execute_judgeSearchButNoKeywords_returnsSkipLlmNoKeywords() = runBlocking {
        val orchestrator = SearchOrchestrator(
            StubSearchClient(),
            // shouldSearch=true 但 keywords=null 且 failed=false
            // 模拟 LLM 判断需要搜索但未给出关键词的边界情况
            StubSearchJudge(SearchDecision(shouldSearch = true, keywords = null, failed = false))
        )
        val settings = WebSearchSettings(enabled = true)

        val outcome = orchestrator.execute(buildContext(settings))

        assertEquals(SearchSkipReason.LLM_NO_KEYWORDS, outcome.skipReason)
    }

    // ---- 搜索结果 ----

    @Test
    fun execute_searchReturnsResults_formatsText() = runBlocking {
        val hits = listOf(
            SearchHit("Result A", "Content A"),
            SearchHit("Result B", "Content B")
        )
        val orchestrator = SearchOrchestrator(
            StubSearchClient(SearchResponse(hits)),
            StubSearchJudge(SearchDecision(shouldSearch = true, keywords = "query"))
        )
        val settings = WebSearchSettings(enabled = true)

        val outcome = orchestrator.execute(buildContext(settings))

        assertTrue(outcome.performed)
        assertEquals(2, outcome.results.size)
        assertEquals("query", outcome.keywords)
        assertNull(outcome.skipReason)
        assertTrue(outcome.formattedText != null)
        assertTrue(outcome.formattedText!!.contains("Result A"))
        assertTrue(outcome.formattedText!!.contains("Result B"))
    }

    @Test
    fun execute_searchReturnsEmpty_returnsSkipNoResults() = runBlocking {
        val orchestrator = SearchOrchestrator(
            StubSearchClient(SearchResponse(emptyList())),
            StubSearchJudge(SearchDecision(shouldSearch = true, keywords = "query"))
        )
        val settings = WebSearchSettings(enabled = true)

        val outcome = orchestrator.execute(buildContext(settings))

        assertTrue(outcome.performed)
        assertEquals(SearchSkipReason.SEARCH_NO_RESULTS, outcome.skipReason)
        assertNull(outcome.formattedText)
    }

    @Test
    fun execute_searchReturnsError_returnsSkipSearchApiError() = runBlocking {
        val orchestrator = SearchOrchestrator(
            StubSearchClient(SearchResponse(emptyList(), errorCode = 401, errorMessage = "Unauthorized")),
            StubSearchJudge(SearchDecision(shouldSearch = true, keywords = "query"))
        )
        val settings = WebSearchSettings(enabled = true)

        val outcome = orchestrator.execute(buildContext(settings))

        assertTrue(outcome.performed)
        assertEquals(SearchSkipReason.SEARCH_API_ERROR, outcome.skipReason)
    }

    @Test
    fun execute_searchThrowsException_returnsSkipSearchApiError() = runBlocking {
        val orchestrator = SearchOrchestrator(
            ThrowingSearchClient(),
            StubSearchJudge(SearchDecision(shouldSearch = true, keywords = "query"))
        )
        val settings = WebSearchSettings(enabled = true)

        val outcome = orchestrator.execute(buildContext(settings))

        assertTrue(outcome.performed)
        assertEquals(SearchSkipReason.SEARCH_API_ERROR, outcome.skipReason)
    }

    // ---- Stubs ----

    private class StubSearchJudge(
        private val decision: SearchDecision = SearchDecision(shouldSearch = false)
    ) : SearchJudge {
        override suspend fun decide(
            provider: `fun`.kirari.hanako.data.ModelProviderConfig,
            model: String,
            questionText: String,
            trustAllHttps: Boolean,
            firstDeltaTimeoutMillis: Long
        ): SearchDecision = decision
    }

    private class StubSearchClient(
        private val response: SearchResponse = SearchResponse(emptyList())
    ) : SearchClient {
        override suspend fun search(
            config: `fun`.kirari.hanako.data.SearchProviderConfig,
            query: String,
            maxResults: Int,
            trustAllHttps: Boolean
        ): SearchResponse = response
    }

    private class ThrowingSearchClient : SearchClient {
        override suspend fun search(
            config: `fun`.kirari.hanako.data.SearchProviderConfig,
            query: String,
            maxResults: Int,
            trustAllHttps: Boolean
        ): SearchResponse = throw RuntimeException("Network error")
    }
}

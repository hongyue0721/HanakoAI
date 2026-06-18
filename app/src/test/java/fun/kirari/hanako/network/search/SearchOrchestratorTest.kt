package `fun`.kirari.hanako.network.search

import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.data.ProviderKind
import `fun`.kirari.hanako.data.SearchProviderConfig
import `fun`.kirari.hanako.data.SearchProviderKind
import `fun`.kirari.hanako.data.WebSearchSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchOrchestratorTest {

    @Test
    fun `execute returns DISABLED when search is disabled`() = runBlocking {
        val orchestrator = SearchOrchestrator(FakeSearchClient(), FakeSearchJudge(shouldSearch = false))
        val outcome = orchestrator.execute(testContext(enabled = false))

        assertFalse(outcome.performed)
        assertEquals(SearchSkipReason.DISABLED, outcome.skipReason)
        assertNull(outcome.formattedText)
    }

    @Test
    fun `execute returns AUTOMATION_DISABLED when automation search not enabled`() = runBlocking {
        val orchestrator = SearchOrchestrator(FakeSearchClient(), FakeSearchJudge(shouldSearch = false))
        val outcome = orchestrator.execute(testContext(enabled = true, isAutomation = true, automationAlsoSearch = false))

        assertFalse(outcome.performed)
        assertEquals(SearchSkipReason.AUTOMATION_DISABLED, outcome.skipReason)
    }

    @Test
    fun `execute returns API_KEY_MISSING when api key is blank`() = runBlocking {
        val orchestrator = SearchOrchestrator(FakeSearchClient(), FakeSearchJudge(shouldSearch = false))
        val outcome = orchestrator.execute(testContext(enabled = true, apiKey = ""))

        assertFalse(outcome.performed)
        assertEquals(SearchSkipReason.API_KEY_MISSING, outcome.skipReason)
    }

    @Test
    fun `execute returns API_URL_MISSING when baseUrl is blank`() = runBlocking {
        val orchestrator = SearchOrchestrator(FakeSearchClient(), FakeSearchJudge(shouldSearch = false))
        val outcome = orchestrator.execute(testContext(enabled = true, baseUrl = ""))

        assertFalse(outcome.performed)
        assertEquals(SearchSkipReason.API_URL_MISSING, outcome.skipReason)
    }

    @Test
    fun `execute returns LLM_JUDGE_NO_SEARCH when judge decides no search needed`() = runBlocking {
        val orchestrator = SearchOrchestrator(FakeSearchClient(), FakeSearchJudge(shouldSearch = false))
        val outcome = orchestrator.execute(testContext(enabled = true))

        assertFalse(outcome.performed)
        assertEquals(SearchSkipReason.LLM_JUDGE_NO_SEARCH, outcome.skipReason)
    }

    @Test
    fun `execute returns LLM_JUDGE_FAILED when judge fails`() = runBlocking {
        val orchestrator = SearchOrchestrator(FakeSearchClient(), FakeSearchJudge(shouldSearch = false, failed = true))
        val outcome = orchestrator.execute(testContext(enabled = true))

        assertFalse(outcome.performed)
        assertEquals(SearchSkipReason.LLM_JUDGE_FAILED, outcome.skipReason)
    }

    @Test
    fun `execute performs search when judge returns keywords`() = runBlocking {
        val fakeSearchClient = FakeSearchClient()
        val orchestrator = SearchOrchestrator(fakeSearchClient, FakeSearchJudge(shouldSearch = true, keywords = "2024 奥运会 举办地"))
        val outcome = orchestrator.execute(testContext(enabled = true))

        assertTrue(outcome.performed)
        assertEquals("2024 奥运会 举办地", outcome.keywords)
        assertEquals(2, outcome.results.size)
        assertNotNull(outcome.formattedText)
        assertTrue(outcome.formattedText!!.contains("Result 1"))
        assertEquals(1, fakeSearchClient.requests.size)
        assertEquals("2024 奥运会 举办地", fakeSearchClient.requests.first().query)
    }

    @Test
    fun `execute returns SEARCH_NO_RESULTS when search returns empty`() = runBlocking {
        val fakeSearchClient = FakeSearchClient(returnEmpty = true)
        val orchestrator = SearchOrchestrator(fakeSearchClient, FakeSearchJudge(shouldSearch = true, keywords = "keyword"))
        val outcome = orchestrator.execute(testContext(enabled = true))

        assertTrue(outcome.performed)
        assertEquals(0, outcome.results.size)
        assertNull(outcome.formattedText)
        assertEquals(SearchSkipReason.SEARCH_NO_RESULTS, outcome.skipReason)
    }

    @Test
    fun `execute returns SEARCH_API_ERROR when search returns error code`() = runBlocking {
        val fakeSearchClient = FakeSearchClient(errorCode = 401, errorMessage = "API Key 无效或权限不足")
        val orchestrator = SearchOrchestrator(fakeSearchClient, FakeSearchJudge(shouldSearch = true, keywords = "keyword"))
        val outcome = orchestrator.execute(testContext(enabled = true))

        assertTrue(outcome.performed)
        assertEquals(0, outcome.results.size)
        assertNull(outcome.formattedText)
        assertEquals(SearchSkipReason.SEARCH_API_ERROR, outcome.skipReason)
    }

    private fun testContext(
        enabled: Boolean,
        apiKey: String = "test-key",
        baseUrl: String = "https://api.tavily.com/search",
        isAutomation: Boolean = false,
        automationAlsoSearch: Boolean = false
    ): SearchContext = SearchContext(
        questionText = "2024年奥运会在哪里举办？",
        settings = WebSearchSettings(
            enabled = enabled,
            provider = SearchProviderConfig(
                kind = SearchProviderKind.TAVILY,
                baseUrl = baseUrl,
                apiKey = apiKey
            ),
            maxResults = 3,
            automationAlsoSearch = automationAlsoSearch
        ),
        llmProvider = ModelProviderConfig(kind = ProviderKind.OPENAI_COMPATIBLE, apiKey = "llm-key"),
        llmModel = "gpt-4o-mini",
        trustAllHttps = false,
        isAutomation = isAutomation
    )

    private class FakeSearchClient(
        private val returnEmpty: Boolean = false,
        private val errorCode: Int = 0,
        private val errorMessage: String? = null
    ) : SearchClient {
        data class Request(val config: SearchProviderConfig, val query: String, val maxResults: Int, val trustAllHttps: Boolean)

        val requests = mutableListOf<Request>()

        override suspend fun search(
            config: SearchProviderConfig,
            query: String,
            maxResults: Int,
            trustAllHttps: Boolean
        ): SearchResponse {
            requests.add(Request(config, query, maxResults, trustAllHttps))
            return if (errorCode != 0) {
                SearchResponse(hits = emptyList(), errorCode = errorCode, errorMessage = errorMessage)
            } else if (returnEmpty) {
                SearchResponse(hits = emptyList())
            } else {
                SearchResponse(hits = listOf(
                    SearchHit("Result 1", "Content 1"),
                    SearchHit("Result 2", "Content 2")
                ))
            }
        }
    }

    private class FakeSearchJudge(
        private val shouldSearch: Boolean,
        private val keywords: String? = null,
        private val failed: Boolean = false
    ) : SearchJudge {
        override suspend fun decide(
            provider: ModelProviderConfig,
            model: String,
            questionText: String,
            trustAllHttps: Boolean,
            firstDeltaTimeoutMillis: Long
        ): SearchDecision = SearchDecision(shouldSearch = shouldSearch, keywords = keywords, failed = failed)
    }
}

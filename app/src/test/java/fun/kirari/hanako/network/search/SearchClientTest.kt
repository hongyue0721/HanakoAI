package `fun`.kirari.hanako.network.search

import `fun`.kirari.hanako.data.SearchProviderKind
import `fun`.kirari.hanako.data.WebSearchSettings
import `fun`.kirari.hanako.data.SearchProviderConfig
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.llm.core.ProviderKind
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SearchClientImpl] 单元测试。
 *
 * 验证 Tavily / Brave / Serper 三种搜索引擎的请求格式和响应解析。
 */
class SearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SearchClientImpl
    private lateinit var networkClientProvider: `fun`.kirari.hanako.network.NetworkClientProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        networkClientProvider = `fun`.kirari.hanako.network.NetworkClientProvider()
        client = SearchClientImpl(networkClientProvider)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun providerConfig(kind: SearchProviderKind, baseUrl: String): SearchProviderConfig {
        return SearchProviderConfig(
            kind = kind,
            baseUrl = baseUrl,
            apiKey = "test-api-key"
        )
    }

    // ---- Tavily ----

    @Test
    fun tavily_searchSendsPostWithApiKeyAndQuery() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"results":[{"title":"Result 1","content":"Content 1"}]}""")
        )
        val config = providerConfig(SearchProviderKind.TAVILY, server.url("/").toString())

        val response = client.search(config, "test query", 3)

        assertEquals(1, response.hits.size)
        assertEquals("Result 1", response.hits[0].title)
        assertEquals("Content 1", response.hits[0].content)
        assertEquals(0, response.errorCode)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"query\":\"test query\""))
        assertTrue(body.contains("\"api_key\":\"test-api-key\""))
    }

    @Test
    fun tavily_returnsEmptyOnEmptyResults() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val config = providerConfig(SearchProviderKind.TAVILY, server.url("/").toString())

        val response = client.search(config, "no results", 3)
        assertTrue(response.hits.isEmpty())
    }

    // ---- Brave ----

    @Test
    fun brave_searchSendsGetWithSubscriptionToken() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"web":{"results":[{"title":"Brave Result","description":"Brave Desc"}]}}""")
        )
        val config = providerConfig(SearchProviderKind.BRAVE, server.url("/").toString())

        val response = client.search(config, "brave query", 5)

        assertEquals(1, response.hits.size)
        assertEquals("Brave Result", response.hits[0].title)
        assertEquals("Brave Desc", response.hits[0].content)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("test-api-key", recorded.getHeader("X-Subscription-Token"))
        // 查询参数应该包含 q=brave query
        val path = recorded.path ?: ""
        assertTrue(path.contains("q="))
        assertTrue(path.contains("count=5"))
    }

    // ---- Serper ----

    @Test
    fun serper_searchSendsPostWithApiKeyHeader() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"organic":[{"title":"Serper Result","snippet":"Serper Snippet"}]}""")
        )
        val config = providerConfig(SearchProviderKind.SERPER, server.url("/").toString())

        val response = client.search(config, "serper query", 10)

        assertEquals(1, response.hits.size)
        assertEquals("Serper Result", response.hits[0].title)
        assertEquals("Serper Snippet", response.hits[0].content)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("test-api-key", recorded.getHeader("X-API-KEY"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"q\":\"serper query\""))
        assertTrue(body.contains("\"num\":10"))
    }

    // ---- HTTP 错误 ----

    @Test
    fun search_returnsErrorCodeOn401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val config = providerConfig(SearchProviderKind.TAVILY, server.url("/").toString())

        val response = client.search(config, "query", 3)
        assertEquals(401, response.errorCode)
        assertTrue(response.errorMessage?.isNotBlank() == true)
        assertTrue(response.hits.isEmpty())
    }
}

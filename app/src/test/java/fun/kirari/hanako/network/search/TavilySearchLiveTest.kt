package `fun`.kirari.hanako.network.search

import `fun`.kirari.hanako.network.NetworkClientProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tavily 真实 API 集成测试。
 *
 * 需要设置环境变量 TAVILY_API_KEY 才会执行，否则自动跳过。
 * 运行方式：TAVILY_API_KEY=tvly-xxx ./gradlew :app:testFullDebugUnitTest --tests "*TavilySearchLiveTest"
 */
class TavilySearchLiveTest {

    @Test
    fun `search returns results from real Tavily API`() = runBlocking {
        val apiKey = System.getenv("TAVILY_API_KEY")
        assumeTrue(
            "Live test skipped: TAVILY_API_KEY not set",
            apiKey != null && apiKey.isNotBlank()
        )

        val adapter = TavilySearchAdapter(NetworkClientProvider())
        val response = adapter.search(
            SearchRequest(
                apiKey = apiKey!!,
                baseUrl = "https://api.tavily.com/search",
                query = "2024 Olympics host city",
                maxResults = 3,
                trustAllHttps = false
            )
        )

        assertTrue("Expected at least one search result", response.hits.isNotEmpty())
        response.hits.forEach { hit ->
            println("[TavilyLive] ${hit.title}: ${hit.content.take(100)}")
        }
    }
}

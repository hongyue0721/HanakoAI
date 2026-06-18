package `fun`.kirari.hanako.network.search

import `fun`.kirari.hanako.network.NetworkClientProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

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

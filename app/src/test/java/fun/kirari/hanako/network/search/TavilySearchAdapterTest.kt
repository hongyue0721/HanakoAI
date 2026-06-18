package `fun`.kirari.hanako.network.search

import `fun`.kirari.hanako.network.NetworkClientProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TavilySearchAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: TavilySearchAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = TavilySearchAdapter(NetworkClientProvider())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search sends correct POST body and parses results`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "results": [
                        { "title": "Result A", "content": "Content A" },
                        { "title": "Result B", "content": "Content B" }
                    ]
                }
                """.trimIndent()
            )
        )

        val response = adapter.search(
            SearchRequest(
                apiKey = "test-key",
                baseUrl = server.url("/").toString(),
                query = "2024 奥运会",
                maxResults = 3,
                trustAllHttps = false
            )
        )

        assertEquals(2, response.hits.size)
        assertEquals("Result A", response.hits[0].title)
        assertEquals("Content A", response.hits[0].content)
        assertEquals(0, response.errorCode)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"api_key\":\"test-key\""))
        assertTrue(body.contains("\"query\":\"2024 奥运会\""))
        assertTrue(body.contains("\"max_results\":3"))
    }

    @Test
    fun `search returns error response on HTTP 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val response = adapter.search(
            SearchRequest(
                apiKey = "test-key",
                baseUrl = server.url("/").toString(),
                query = "test",
                maxResults = 3,
                trustAllHttps = false
            )
        )

        assertEquals(0, response.hits.size)
        assertEquals(401, response.errorCode)
        assertTrue(response.errorMessage!!.contains("API Key"))
    }

    @Test
    fun `search returns error response on HTTP 429`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))

        val response = adapter.search(
            SearchRequest(
                apiKey = "test-key",
                baseUrl = server.url("/").toString(),
                query = "test",
                maxResults = 3,
                trustAllHttps = false
            )
        )

        assertEquals(0, response.hits.size)
        assertEquals(429, response.errorCode)
        assertTrue(response.errorMessage!!.contains("频率"))
    }

    @Test
    fun `search returns empty hits when results missing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = adapter.search(
            SearchRequest(
                apiKey = "test-key",
                baseUrl = server.url("/").toString(),
                query = "test",
                maxResults = 3,
                trustAllHttps = false
            )
        )

        assertEquals(0, response.hits.size)
        assertEquals(0, response.errorCode)
    }
}

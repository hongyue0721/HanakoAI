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

class SerperSearchAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: SerperSearchAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = SerperSearchAdapter(NetworkClientProvider())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search sends correct POST body with API key header`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "organic": [
                        { "title": "Serper A", "snippet": "Snippet A" },
                        { "title": "Serper B", "snippet": "Snippet B" }
                    ]
                }
                """.trimIndent()
            )
        )

        val response = adapter.search(
            SearchRequest(
                apiKey = "serper-key",
                baseUrl = server.url("/").toString(),
                query = "latest news",
                maxResults = 4,
                trustAllHttps = false
            )
        )

        assertEquals(2, response.hits.size)
        assertEquals("Serper A", response.hits[0].title)
        assertEquals("Snippet A", response.hits[0].content)
        assertEquals(0, response.errorCode)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("serper-key", request.getHeader("X-API-KEY"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"q\":\"latest news\""))
        assertTrue(body.contains("\"num\":4"))
    }

    @Test
    fun `search returns error response on HTTP 429`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))

        val response = adapter.search(
            SearchRequest(
                apiKey = "serper-key",
                baseUrl = server.url("/").toString(),
                query = "test",
                maxResults = 3,
                trustAllHttps = false
            )
        )

        assertEquals(0, response.hits.size)
        assertEquals(429, response.errorCode)
        assertTrue(response.errorMessage?.contains("频率") == true)
    }
}

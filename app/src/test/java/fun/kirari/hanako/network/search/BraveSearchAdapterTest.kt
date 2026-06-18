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

class BraveSearchAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: BraveSearchAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = BraveSearchAdapter(NetworkClientProvider())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search sends correct GET request with auth header`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "web": {
                        "results": [
                            { "title": "Brave A", "description": "Desc A" },
                            { "title": "Brave B", "description": "Desc B" }
                        ]
                    }
                }
                """.trimIndent()
            )
        )

        val response = adapter.search(
            SearchRequest(
                apiKey = "brave-token",
                baseUrl = server.url("/").toString(),
                query = "current president",
                maxResults = 5,
                trustAllHttps = false
            )
        )

        assertEquals(2, response.hits.size)
        assertEquals("Brave A", response.hits[0].title)
        assertEquals("Desc A", response.hits[0].content)
        assertEquals(0, response.errorCode)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("brave-token", request.getHeader("X-Subscription-Token"))
        val path = request.path
        assertTrue(path?.contains("q=current%20president") == true)
        assertTrue(path?.contains("count=5") == true)
    }

    @Test
    fun `search returns error response on HTTP 403`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        val response = adapter.search(
            SearchRequest(
                apiKey = "brave-token",
                baseUrl = server.url("/").toString(),
                query = "test",
                maxResults = 3,
                trustAllHttps = false
            )
        )

        assertEquals(0, response.hits.size)
        assertEquals(403, response.errorCode)
        assertTrue(response.errorMessage!!.contains("API Key"))
    }
}

package `fun`.kirari.llm.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ProviderModelsApi] 单元测试。
 *
 * 验证内容：
 * - 四种 provider kind 的鉴权 header（Bearer / x-api-key / x-goog-api-key）
 * - 错误消息解析
 * - 空 API Key 早返回（不发网络请求）
 */
class ProviderModelsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ProviderModelsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ProviderModelsApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- 空 API Key 早返回 ----

    @Test
    fun testConnection_returnsFailedWithoutNetworkCallWhenApiKeyIsBlank() = runBlocking {
        val provider = ProviderConfig(
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = server.url("/").toString(),
            apiKey = ""
        )
        val result = api.testConnection(provider)
        assertFalse(result.success)
        assertEquals("请填写 API 密钥", result.errorMessage)
        // 不应该发出任何请求
        assertEquals(0, server.requestCount)
    }

    @Test
    fun testConnection_returnsFailedForGoogleWithBlankKey() = runBlocking {
        val provider = ProviderConfig(
            kind = ProviderKind.GOOGLE,
            baseUrl = server.url("/").toString(),
            apiKey = ""
        )
        val result = api.testConnection(provider)
        assertFalse(result.success)
        assertEquals("请填写 API 密钥", result.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun testConnection_returnsFailedForAnthropicWithBlankKey() = runBlocking {
        val provider = ProviderConfig(
            kind = ProviderKind.ANTHROPIC,
            baseUrl = server.url("/").toString(),
            apiKey = ""
        )
        val result = api.testConnection(provider)
        assertFalse(result.success)
        assertEquals("请填写 API 密钥", result.errorMessage)
        assertEquals(0, server.requestCount)
    }

    // ---- 鉴权 Header 验证 ----

    @Test
    fun testConnection_openAiCompatibleSendsBearerToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val provider = ProviderConfig(
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = server.url("/").toString(),
            apiKey = "sk-test-key"
        )
        val result = api.testConnection(provider)
        assertTrue(result.success)
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun testConnection_googleSendsXGoogApiKey() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        val provider = ProviderConfig(
            kind = ProviderKind.GOOGLE,
            baseUrl = server.url("/").toString(),
            apiKey = "google-test-key"
        )
        val result = api.testConnection(provider)
        assertTrue(result.success)
        val recorded = server.takeRequest()
        assertEquals("google-test-key", recorded.getHeader("x-goog-api-key"))
        // Google 不应该用 Bearer
        assert(recorded.getHeader("Authorization") == null)
    }

    @Test
    fun testConnection_anthropicSendsXApiKey() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val provider = ProviderConfig(
            kind = ProviderKind.ANTHROPIC,
            baseUrl = server.url("/").toString(),
            apiKey = "anthropic-test-key"
        )
        val result = api.testConnection(provider)
        assertTrue(result.success)
        val recorded = server.takeRequest()
        assertEquals("anthropic-test-key", recorded.getHeader("x-api-key"))
        // Anthropic 不应该用 Bearer
        assert(recorded.getHeader("Authorization") == null)
    }

    // ---- 错误消息解析 ----

    @Test
    fun testConnection_returnsErrorMessageOn401() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key"}}""")
        )
        val provider = ProviderConfig(
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = server.url("/").toString(),
            apiKey = "bad-key"
        )
        val result = api.testConnection(provider)
        assertFalse(result.success)
        assertEquals("Invalid API key", result.errorMessage)
    }

    @Test
    fun testConnection_returnsHttpCodeWhenNoErrorMessageInBody() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )
        val provider = ProviderConfig(
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = server.url("/").toString(),
            apiKey = "test-key"
        )
        val result = api.testConnection(provider)
        assertFalse(result.success)
        assertEquals("HTTP 500", result.errorMessage)
    }

    // ---- listModels 鉴权 ----

    @Test
    fun listModels_openAiCompatibleSendsBearerToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-4o"}]}"""))
        val provider = ProviderConfig(
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = server.url("/").toString(),
            apiKey = "sk-list-key"
        )
        val models = api.listModels(provider)
        assertEquals(1, models.size)
        assertEquals("gpt-4o", models[0].id)
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-list-key", recorded.getHeader("Authorization"))
    }
}

package com.smsairelay.app

import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Real HTTP against a local MockWebServer; no provider is ever contacted.
class AiHttpTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun postJsonSendsHeadersAndJsonBodyAndReturnsResponse() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val result = AiHttp.postJson(
            server.url("/v1/test").toString(),
            mapOf("x-api-key" to "secret"),
            JSONObject().put("hello", "world")
        )

        assertEquals("""{"ok":true}""", result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/test", request.path)
        assertEquals("secret", request.getHeader("x-api-key"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("world", JSONObject(request.body.readUtf8()).getString("hello"))
    }

    @Test
    fun postJsonThrowsWithStatusAndBodyOnHttpError() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))

        val error = runCatching {
            AiHttp.postJson(server.url("/").toString(), emptyMap(), JSONObject())
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("HTTP 401: bad key", error!!.message)
    }

    @Test
    fun callWrapsIoAndJsonFailuresAsResultFailure() {
        assertTrue(AiHttp.call { throw IOException("offline") }.isFailure)
        assertTrue(AiHttp.call { throw JSONException("bad json") }.isFailure)
        assertEquals("fine", AiHttp.call { "fine" }.getOrThrow())
    }

    @Test(expected = IllegalStateException::class)
    fun callDoesNotSwallowUnexpectedExceptions() {
        AiHttp.call { throw IllegalStateException("bug") }
    }

    // --- OpenAiClient end to end: it's the one client with a configurable base URL ---

    private val client get() = OpenAiClient(server.url("/v1/").toString(), "test-model")

    @Test
    fun openAiCompleteHitsChatCompletionsWithBearerKey() {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"content":"Sunny."}}]}""")
        )

        val result = client.complete(
            apiKey = "sk-test",
            model = "test-model",
            systemPrompt = "sys",
            turns = listOf(ChatTurn(ChatTurn.Role.USER, "weather?"))
        )

        assertEquals("Sunny.", result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("weather?", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun openAiCompleteReturnsFailureOnHttpError() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val result = client.complete("sk-test", "test-model", "sys", emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun openAiCompleteReturnsFailureOnMalformedBody() {
        server.enqueue(MockResponse().setBody("not json"))
        val result = client.complete("sk-test", "test-model", "sys", emptyList())
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is IOException)
    }

    @Test
    fun compatibleClientUsesItsBaseUrlAndTokenField() {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"<think>hmm</think>Bonjour."}}]}"""
            )
        )
        val mistralStyle = OpenAiClient(
            baseUrl = server.url("/v1").toString(),
            defaultModel = "small",
            maxTokensField = "max_tokens",
            providerName = "Test"
        )

        val result = mistralStyle.complete("key", "small", "sys", listOf(ChatTurn(ChatTurn.Role.USER, "hi")))

        assertEquals("Bonjour.", result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.has("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
    }
}

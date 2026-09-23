package com.smsairelay.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val SMS_SYSTEM_PROMPT =
    "You are a helpful assistant replying over SMS. Keep answers short, ideally " +
        "a sentence or two, since replies are sent as text messages. Plain text " +
        "only: no markdown, no bullet points, no headers."

data class ChatTurn(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

// Takes a list of turns rather than one message so Phase 3 can pass history
// without changing any provider.
interface AiClient {
    // Provider-specific tuning (thinking/reasoning level) is only sent for this model,
    // since other models the user types in may reject those parameters.
    val defaultModel: String

    fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): Result<String>
}

enum class AiProvider(val displayName: String, val keyHint: String, val client: AiClient) {
    CLAUDE("Claude (Anthropic)", "sk-ant-…", ClaudeClient),
    GEMINI("Gemini (Google)", "AIza…", GeminiClient),
    OPENAI("OpenAI", "sk-…", OpenAiClient.OPENAI);

    override fun toString() = displayName
}

internal object AiHttp {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun postJson(url: String, headers: Map<String, String>, body: JSONObject): String {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $bodyText")
            return bodyText
        }
    }

    // Network and malformed-response failures become a Result instead of crashing the receiver.
    inline fun call(block: () -> String): Result<String> =
        try {
            Result.success(block())
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: JSONException) {
            Result.failure(e)
        }
}

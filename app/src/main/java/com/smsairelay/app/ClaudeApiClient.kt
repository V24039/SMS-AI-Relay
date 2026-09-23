package com.smsairelay.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// One-shot request/response for now — Phase 3 adds the message-history array here.
object ClaudeApiClient {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val MAX_TOKENS = 512

    private const val SYSTEM_PROMPT =
        "You are a helpful assistant replying over SMS. Keep answers short, ideally " +
            "a sentence or two, since replies are sent as text messages. Plain text " +
            "only: no markdown, no bullet points, no headers."

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun sendMessage(apiKey: String, userMessage: String): Result<String> {
        val requestJson = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", SYSTEM_PROMPT)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    }
                )
            )
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(IOException("Claude API error ${response.code}: $bodyText"))
                } else {
                    Result.success(extractText(bodyText))
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: JSONException) {
            Result.failure(e)
        }
    }

    // Joins every text block rather than assuming content[0] is text.
    private fun extractText(bodyText: String): String {
        val content = JSONObject(bodyText).getJSONArray("content")
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }.trim()
        if (text.isEmpty()) throw JSONException("Response had no text content")
        return text
    }
}

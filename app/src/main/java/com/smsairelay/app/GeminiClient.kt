package com.smsairelay.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object GeminiClient : AiClient {

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/"

    // Thinking tokens count against this, so it's well above what an SMS reply needs;
    // reply length is controlled by the system prompt instead.
    private const val MAX_OUTPUT_TOKENS = 2048

    override val defaultModel = "gemini-3.8-flash"

    override fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): Result<String> = AiHttp.call {
        // Path segment encoding keeps a user-typed model name from escaping the URL path.
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("models")
            .addPathSegment("$model:generateContent")
            .build()
            .toString()
        val response = AiHttp.postJson(
            url,
            mapOf("x-goog-api-key" to apiKey),
            buildBody(model, systemPrompt, turns)
        )
        parseReply(response)
    }

    internal fun buildBody(model: String, systemPrompt: String, turns: List<ChatTurn>) =
        JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", textParts(systemPrompt)))
            put("contents", JSONArray().apply {
                turns.forEach { turn ->
                    put(JSONObject().apply {
                        put("role", if (turn.role == ChatTurn.Role.USER) "user" else "model")
                        put("parts", textParts(turn.text))
                    })
                }
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                // Gemini 3.8 Flash can't disable thinking; "low" keeps latency down so the
                // reply finishes inside the receiver's time window.
                if (model == defaultModel) {
                    put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                }
            })
        }

    internal fun parseReply(bodyText: String): String {
        val json = JSONObject(bodyText)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val reason = json.optJSONObject("promptFeedback")?.optString("blockReason")
            throw JSONException("Gemini returned no candidates (blockReason=$reason)")
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: JSONArray()
        val text = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (!part.optBoolean("thought")) append(part.optString("text"))
            }
        }.trim()
        if (text.isEmpty()) throw JSONException("Gemini response had no text content")
        return text
    }

    private fun textParts(text: String) = JSONArray().put(JSONObject().put("text", text))
}

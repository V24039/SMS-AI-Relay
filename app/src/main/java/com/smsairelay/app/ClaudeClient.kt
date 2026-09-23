package com.smsairelay.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object ClaudeClient : AiClient {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val MAX_TOKENS = 512

    override val defaultModel = "claude-haiku-4-5"

    override fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): Result<String> = AiHttp.call {
        val response = AiHttp.postJson(
            ENDPOINT,
            mapOf("x-api-key" to apiKey, "anthropic-version" to ANTHROPIC_VERSION),
            buildBody(model, systemPrompt, turns)
        )
        parseReply(response)
    }

    internal fun buildBody(model: String, systemPrompt: String, turns: List<ChatTurn>) =
        JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                turns.forEach { turn ->
                    put(JSONObject().apply {
                        put("role", if (turn.role == ChatTurn.Role.USER) "user" else "assistant")
                        put("content", turn.text)
                    })
                }
            })
        }

    // Joins every text block rather than assuming content[0] is text.
    internal fun parseReply(bodyText: String): String {
        val content = JSONObject(bodyText).getJSONArray("content")
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }.trim()
        if (text.isEmpty()) throw JSONException("Claude response had no text content")
        return text
    }
}

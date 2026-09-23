package com.smsairelay.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Uses Chat Completions rather than OpenAI's newer Responses API because it's the
// de facto standard: Groq, OpenRouter, Mistral, Cerebras etc. accept the same request,
// so adding them later is just another instance with a different baseUrl.
class OpenAiClient(
    private val baseUrl: String,
    override val defaultModel: String,
    // Only sent for defaultModel; not every model (or compatible provider) accepts it.
    private val defaultReasoningEffort: String? = null
) : AiClient {

    override fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): Result<String> = AiHttp.call {
        val response = AiHttp.postJson(
            "${baseUrl.trimEnd('/')}/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey"),
            buildBody(model, systemPrompt, turns)
        )
        parseReply(response)
    }

    internal fun buildBody(model: String, systemPrompt: String, turns: List<ChatTurn>) =
        JSONObject().apply {
            put("model", model)
            // Reasoning models reject max_tokens; this one also caps any reasoning tokens.
            put("max_completion_tokens", MAX_COMPLETION_TOKENS)
            if (model == defaultModel && defaultReasoningEffort != null) {
                put("reasoning_effort", defaultReasoningEffort)
            }
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                turns.forEach { turn ->
                    put(JSONObject().apply {
                        put("role", if (turn.role == ChatTurn.Role.USER) "user" else "assistant")
                        put("content", turn.text)
                    })
                }
            })
        }

    internal fun parseReply(bodyText: String): String {
        val choices = JSONObject(bodyText).getJSONArray("choices")
        if (choices.length() == 0) throw JSONException("OpenAI response had no choices")
        val choice = choices.getJSONObject(0)
        val message = choice.getJSONObject("message")
        val text = if (message.isNull("content")) "" else message.getString("content").trim()
        if (text.isEmpty()) {
            throw JSONException(
                "OpenAI response had no text (finish_reason=${choice.optString("finish_reason")})"
            )
        }
        return text
    }

    companion object {
        private const val MAX_COMPLETION_TOKENS = 1024

        val OPENAI = OpenAiClient(
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-6-luna",
            defaultReasoningEffort = "none"
        )
    }
}

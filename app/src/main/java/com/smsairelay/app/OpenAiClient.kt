package com.smsairelay.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Uses Chat Completions rather than OpenAI's newer Responses API because it's the
// de facto standard: Groq, OpenRouter, Mistral, Cerebras etc. accept the same request,
// so each of them is just another instance with a different baseUrl.
class OpenAiClient(
    private val baseUrl: String,
    override val defaultModel: String,
    // Only sent for defaultModel; not every model (or compatible provider) accepts it.
    private val defaultReasoningEffort: String? = null,
    // OpenAI's reasoning models reject max_tokens, but some compatible providers only
    // document max_tokens, so the field name is per provider.
    private val maxTokensField: String = "max_completion_tokens",
    // Used in error messages, so logs say which provider failed.
    private val providerName: String = "OpenAI"
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
            // Also caps any reasoning tokens, so it's well above what an SMS reply needs.
            put(maxTokensField, MAX_COMPLETION_TOKENS)
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
        if (choices.length() == 0) throw JSONException("$providerName response had no choices")
        val choice = choices.getJSONObject(0)
        val message = choice.getJSONObject("message")
        val raw = if (message.isNull("content")) "" else message.getString("content")
        val text = stripThinking(raw)
        if (text.isEmpty()) {
            throw JSONException(
                "$providerName response had no text (finish_reason=${choice.optString("finish_reason")})"
            )
        }
        return text
    }

    // Some open reasoning models (e.g. Qwen on Groq, or whatever OpenRouter's free router
    // picks) put their reasoning in the reply as <think>...</think>; it must not be texted.
    // An unclosed <think> means the reply was cut off mid-reasoning, so there's no answer.
    internal fun stripThinking(text: String): String {
        val rest = THINK_BLOCK.replace(text, "")
        return if (rest.trimStart().startsWith("<think>")) "" else rest.trim()
    }

    companion object {
        private const val MAX_COMPLETION_TOKENS = 1024

        private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

        val OPENAI = OpenAiClient(
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-6-luna",
            defaultReasoningEffort = "none"
        )

        // The providers below all have free tiers (check each one's current limits).

        // Production, non-reasoning model: fast, and nothing to strip.
        val GROQ = OpenAiClient(
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
            providerName = "Groq"
        )

        // Returns reasoning in a separate field; "low" keeps latency down.
        val CEREBRAS = OpenAiClient(
            baseUrl = "https://api.cerebras.ai/v1",
            defaultModel = "gpt-oss-120b",
            defaultReasoningEffort = "low",
            providerName = "Cerebras"
        )

        // Mistral's API documents max_tokens only.
        val MISTRAL = OpenAiClient(
            baseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-small-latest",
            maxTokensField = "max_tokens",
            providerName = "Mistral"
        )

        // "openrouter/free" routes each request to a currently free model at random.
        val OPENROUTER = OpenAiClient(
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openrouter/free",
            providerName = "OpenRouter"
        )
    }
}

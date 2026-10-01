package com.smsairelay.app

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientsTest {

    private val turns = listOf(
        ChatTurn(ChatTurn.Role.USER, "hi"),
        ChatTurn(ChatTurn.Role.ASSISTANT, "hello"),
        ChatTurn(ChatTurn.Role.USER, "weather?")
    )

    // --- Claude ---

    @Test
    fun claudeBodyUsesTopLevelSystemAndRoles() {
        val body = ClaudeClient.buildBody("claude-haiku-4-5", "sys", turns)
        assertEquals("sys", body.getString("system"))
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun claudeParseJoinsTextBlocksAndSkipsOthers() {
        val reply = ClaudeClient.parseReply(
            """{"content":[{"type":"thinking","thinking":""},
                {"type":"text","text":"Sunny "},{"type":"text","text":"today."}]}"""
        )
        assertEquals("Sunny today.", reply)
    }

    @Test(expected = JSONException::class)
    fun claudeParseFailsWithoutText() {
        ClaudeClient.parseReply("""{"content":[]}""")
    }

    // --- Gemini ---

    @Test
    fun geminiBodyMapsAssistantToModelRole() {
        val body = GeminiClient.GEMINI.buildBody("gemini-3.8-flash", "sys", turns)
        val contents = body.getJSONArray("contents")
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals(
            "sys",
            body.getJSONObject("systemInstruction").getJSONArray("parts")
                .getJSONObject(0).getString("text")
        )
    }

    @Test
    fun geminiThinkingLevelOnlyForDefaultModel() {
        val tuned = GeminiClient.GEMINI.buildBody(GeminiClient.GEMINI.defaultModel, "sys", turns)
        assertEquals(
            "low",
            tuned.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
                .getString("thinkingLevel")
        )
        val custom = GeminiClient.GEMINI.buildBody("gemini-2.5-flash", "sys", turns)
        assertFalse(custom.getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test
    fun gemmaUsesMinimalThinkingAndSystemInstruction() {
        val body = GeminiClient.GEMMA.buildBody(GeminiClient.GEMMA.defaultModel, "sys", turns)
        assertEquals(
            "minimal",
            body.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
                .getString("thinkingLevel")
        )
        assertTrue(body.has("systemInstruction"))
    }

    @Test
    fun geminiAndGemmaShareOneApiKey() {
        assertEquals(AiProvider.GEMINI.keySlot, AiProvider.GEMMA.keySlot)
    }

    @Test
    fun geminiParseSkipsThoughtParts() {
        val reply = GeminiClient.GEMINI.parseReply(
            """{"candidates":[{"content":{"role":"model","parts":[
                {"text":"thinking...","thought":true},{"text":"It's 25C."}]}}]}"""
        )
        assertEquals("It's 25C.", reply)
    }

    @Test
    fun geminiParseReportsBlockedPrompt() {
        try {
            GeminiClient.GEMINI.parseReply("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
            throw AssertionError("expected JSONException")
        } catch (e: JSONException) {
            assertTrue(e.message!!.contains("SAFETY"))
        }
    }

    // --- OpenAI ---

    @Test
    fun openAiBodyPutsSystemFirstAndUsesMaxCompletionTokens() {
        val body = OpenAiClient.OPENAI.buildBody("gpt-6-luna", "sys", turns)
        val messages = body.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(body.has("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
    }

    @Test
    fun openAiReasoningEffortOnlyForDefaultModel() {
        val tuned = OpenAiClient.OPENAI.buildBody(OpenAiClient.OPENAI.defaultModel, "sys", turns)
        assertEquals("none", tuned.getString("reasoning_effort"))
        val custom = OpenAiClient.OPENAI.buildBody("gpt-4o-mini", "sys", turns)
        assertFalse(custom.has("reasoning_effort"))
    }

    @Test
    fun openAiParseReadsFirstChoice() {
        val reply = OpenAiClient.OPENAI.parseReply(
            """{"choices":[{"message":{"role":"assistant","content":" Yes. "},
                "finish_reason":"stop"}]}"""
        )
        assertEquals("Yes.", reply)
    }

    @Test
    fun openAiParseFailsOnNullContent() {
        try {
            OpenAiClient.OPENAI.parseReply(
                """{"choices":[{"message":{"content":null},"finish_reason":"length"}]}"""
            )
            throw AssertionError("expected JSONException")
        } catch (e: JSONException) {
            assertTrue(e.message!!.contains("length"))
        }
    }

    // --- OpenAI-compatible providers ---

    private fun reply(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${org.json.JSONObject.quote(content)}},"finish_reason":"stop"}]}"""

    @Test
    fun mistralSendsMaxTokensInsteadOfMaxCompletionTokens() {
        val body = OpenAiClient.MISTRAL.buildBody(OpenAiClient.MISTRAL.defaultModel, "sys", turns)
        assertTrue(body.has("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
    }

    @Test
    fun groqCerebrasAndOpenRouterSendMaxCompletionTokens() {
        listOf(OpenAiClient.GROQ, OpenAiClient.CEREBRAS, OpenAiClient.OPENROUTER).forEach {
            val body = it.buildBody(it.defaultModel, "sys", turns)
            assertTrue(body.has("max_completion_tokens"))
            assertFalse(body.has("max_tokens"))
        }
    }

    @Test
    fun onlyCerebrasDefaultModelGetsReasoningEffort() {
        val cerebras = OpenAiClient.CEREBRAS
        assertEquals("low", cerebras.buildBody(cerebras.defaultModel, "sys", turns).getString("reasoning_effort"))
        assertFalse(cerebras.buildBody("qwen-3.8-27b", "sys", turns).has("reasoning_effort"))
        listOf(OpenAiClient.GROQ, OpenAiClient.MISTRAL, OpenAiClient.OPENROUTER).forEach {
            assertFalse(it.buildBody(it.defaultModel, "sys", turns).has("reasoning_effort"))
        }
    }

    @Test
    fun parseStripsThinkBlocks() {
        assertEquals(
            "Paris.",
            OpenAiClient.GROQ.parseReply(reply("<think>\nThe capital of France...\n</think>\n\nParis."))
        )
    }

    @Test
    fun parseStripsSeveralThinkBlocksAnywhere() {
        assertEquals(
            "A B",
            OpenAiClient.OPENROUTER.parseReply(reply("<think>x</think>A <think>y\nz</think>B"))
        )
    }

    // Cut off by the token limit while still reasoning: there's no answer to send.
    @Test
    fun unclosedThinkBlockMeansNoAnswer() {
        try {
            OpenAiClient.OPENROUTER.parseReply(reply("<think>still reasoning when the limit hit"))
            throw AssertionError("expected JSONException")
        } catch (e: JSONException) {
            assertTrue(e.message!!.contains("OpenRouter"))
        }
    }

    @Test
    fun textWithoutThinkTagsIsUntouched() {
        assertEquals("a < b and c > d", OpenAiClient.MISTRAL.stripThinking("  a < b and c > d "))
    }

    @Test
    fun errorsNameTheProvider() {
        try {
            OpenAiClient.MISTRAL.parseReply("""{"choices":[]}""")
            throw AssertionError("expected JSONException")
        } catch (e: JSONException) {
            assertEquals("Mistral response had no choices", e.message)
        }
    }

    @Test
    fun compatibleProvidersUseOpenAiClientAndTheirOwnKeySlot() {
        val compatible = listOf(AiProvider.GROQ, AiProvider.CEREBRAS, AiProvider.MISTRAL, AiProvider.OPENROUTER)
        compatible.forEach { assertTrue(it.client is OpenAiClient) }
        // Only Gemini and Gemma share a key; every other provider has its own.
        val slots = AiProvider.entries.map { it.keySlot }
        assertEquals(slots.size - 1, slots.toSet().size)
    }
}

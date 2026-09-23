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
}

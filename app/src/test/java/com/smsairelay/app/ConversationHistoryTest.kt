package com.smsairelay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryTest {

    private fun user(text: String) = ChatTurn(ChatTurn.Role.USER, text)
    private fun assistant(text: String) = ChatTurn(ChatTurn.Role.ASSISTANT, text)

    @Test
    fun appendsNewMessageAsFinalUserTurn() {
        val request = ConversationHistory.buildRequest(
            listOf(user("hi"), assistant("hello")), "what about the second one?"
        )
        assertEquals(
            listOf(user("hi"), assistant("hello"), user("what about the second one?")),
            request
        )
    }

    @Test
    fun emptyHistoryYieldsJustTheNewMessage() {
        assertEquals(listOf(user("hi")), ConversationHistory.buildRequest(emptyList(), "hi"))
    }

    // Each single-character turn estimates to 1 token.
    @Test
    fun trimsOldestTurnsToFitTokenBudget() {
        val history = listOf(user("1"), assistant("2"), user("3"), assistant("4"))
        val request = ConversationHistory.buildRequest(history, "5", tokenBudget = 3)
        assertEquals(listOf(user("3"), assistant("4"), user("5")), request)
    }

    @Test
    fun dropsLeadingAssistantTurnLeftByTrimming() {
        val history = listOf(user("1"), assistant("2"), user("3"), assistant("4"))
        val request = ConversationHistory.buildRequest(history, "5", tokenBudget = 4)
        assertEquals(listOf(user("3"), assistant("4"), user("5")), request)
    }

    @Test
    fun oversizedNewMessageIsStillSentAlone() {
        val history = listOf(user("hi"), assistant("hello"))
        val longMessage = "x".repeat(100)
        assertEquals(
            listOf(user(longMessage)),
            ConversationHistory.buildRequest(history, longMessage, tokenBudget = 10)
        )
    }

    @Test
    fun recognisesResetCommandsCaseInsensitively() {
        assertTrue(ConversationHistory.isResetCommand("/new"))
        assertTrue(ConversationHistory.isResetCommand("  RESET "))
        assertTrue(ConversationHistory.isResetCommand("Reset"))
        assertFalse(ConversationHistory.isResetCommand("reset my password please"))
        assertFalse(ConversationHistory.isResetCommand("/newton"))
    }

    @Test
    fun stopCommandMatchesOnlyTheWholeMessage() {
        assertTrue(ConversationHistory.isStopCommand("STOP"))
        assertTrue(ConversationHistory.isStopCommand(" /stop "))
        assertFalse(ConversationHistory.isStopCommand("stop the car"))
        assertFalse(ConversationHistory.isStopCommand(""))
    }

    @Test
    fun splitForSmsKeepsShortTextWhole() {
        assertEquals(listOf("hello"), ConversationHistory.splitForSms("hello"))
        assertEquals(listOf(""), ConversationHistory.splitForSms(""))
    }

    @Test
    fun splitForSmsBreaksAtWordsWithinTheLimit() {
        val chunks = ConversationHistory.splitForSms("alpha beta gamma delta", maxChars = 11)
        assertEquals(listOf("alpha beta", "gamma delta"), chunks)
    }

    @Test
    fun splitForSmsHardCutsTextWithoutSpaces() {
        val chunks = ConversationHistory.splitForSms("x".repeat(25), maxChars = 10)
        assertEquals(listOf(10, 10, 5), chunks.map { it.length })
    }

    @Test
    fun invalidIdleTimeoutFallsBackToDefault() {
        val default = ConversationHistory.DEFAULT_IDLE_TIMEOUT_MINUTES
        assertEquals(30, ConversationHistory.parseIdleTimeoutMinutes(" 30 "))
        assertEquals(default, ConversationHistory.parseIdleTimeoutMinutes(""))
        assertEquals(default, ConversationHistory.parseIdleTimeoutMinutes("0"))
        assertEquals(default, ConversationHistory.parseIdleTimeoutMinutes("-5"))
        assertEquals(default, ConversationHistory.parseIdleTimeoutMinutes("abc"))
    }

    @Test
    fun toTurnsMapsStoredRoles() {
        val stored = listOf(
            ChatMessage(1, "k", "USER", "hi", 0),
            ChatMessage(2, "k", "ASSISTANT", "hello", 0)
        )
        assertEquals(listOf(user("hi"), assistant("hello")), ConversationHistory.toTurns(stored))
    }

    @Test
    fun sameNumberInDifferentFormatsSharesOneConversation() {
        assertEquals(
            SenderAllowlist.conversationKey("+919876543210"),
            SenderAllowlist.conversationKey("098765 43210")
        )
    }

    @Test
    fun estimateTokensRoundsUpPerFourChars() {
        assertEquals(0, ConversationHistory.estimateTokens(""))
        assertEquals(1, ConversationHistory.estimateTokens("a"))
        assertEquals(1, ConversationHistory.estimateTokens("abcd"))
        assertEquals(2, ConversationHistory.estimateTokens("abcde"))
    }

    @Test
    fun nonIntegerIdleTimeoutFallsBackToDefault() {
        assertEquals(
            ConversationHistory.DEFAULT_IDLE_TIMEOUT_MINUTES,
            ConversationHistory.parseIdleTimeoutMinutes("1.5")
        )
    }

    // A turn that exactly uses up the remaining budget is still kept.
    @Test
    fun turnExactlyFillingBudgetIsKept() {
        val history = listOf(user("1"), assistant("2"))
        val request = ConversationHistory.buildRequest(history, "3", tokenBudget = 3)
        assertEquals(listOf(user("1"), assistant("2"), user("3")), request)
    }

    @Test
    fun resetCommandMatchesOnlyTheExactWords() {
        assertFalse(ConversationHistory.isResetCommand(""))
        assertFalse(ConversationHistory.isResetCommand("new"))
        assertTrue(ConversationHistory.isResetCommand("/NEW"))
    }
}

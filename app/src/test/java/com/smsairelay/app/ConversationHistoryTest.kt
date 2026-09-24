package com.smsairelay.app

import org.junit.Assert.assertEquals
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

    @Test
    fun trimsToMaxTurnsKeepingNewest() {
        val history = listOf(user("1"), assistant("2"), user("3"), assistant("4"))
        val request = ConversationHistory.buildRequest(history, "5", maxTurns = 3)
        assertEquals(listOf(user("3"), assistant("4"), user("5")), request)
    }

    @Test
    fun dropsLeadingAssistantTurnLeftByTrimming() {
        val history = listOf(user("1"), assistant("2"), user("3"), assistant("4"))
        val request = ConversationHistory.buildRequest(history, "5", maxTurns = 4)
        assertEquals(listOf(user("3"), assistant("4"), user("5")), request)
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
}

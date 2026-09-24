package com.smsairelay.app

import android.content.Context

// Pure logic (no Android types) so it stays unit-testable. Phase 4 replaces the
// fixed turn cap with the inactivity/keyword/token-budget reset policy.
object ConversationHistory {

    const val MAX_TURNS = 20

    fun toTurns(stored: List<ChatMessage>): List<ChatTurn> =
        stored.map { ChatTurn(ChatTurn.Role.valueOf(it.role), it.content) }

    // Providers require the conversation to open with a user turn, and trimming to the
    // last N can cut an exchange in half, so leading assistant turns are dropped.
    fun buildRequest(history: List<ChatTurn>, newMessage: String, maxTurns: Int = MAX_TURNS): List<ChatTurn> {
        val turns = (history + ChatTurn(ChatTurn.Role.USER, newMessage)).takeLast(maxTurns)
        return turns.dropWhile { it.role == ChatTurn.Role.ASSISTANT }
    }
}

class ConversationRepository(context: Context) {

    private val dao = ChatDatabase.get(context).messageDao()

    suspend fun load(conversationKey: String): List<ChatTurn> =
        ConversationHistory.toTurns(dao.recent(conversationKey, ConversationHistory.MAX_TURNS))

    // Saved together, and only after the model answered, so a failed call never leaves
    // an unanswered user turn behind to break the alternation on the next message.
    suspend fun saveExchange(conversationKey: String, userText: String, replyText: String) {
        val now = System.currentTimeMillis()
        dao.insertAll(
            listOf(
                ChatMessage(
                    conversationKey = conversationKey,
                    role = ChatTurn.Role.USER.name,
                    content = userText,
                    timestamp = now
                ),
                ChatMessage(
                    conversationKey = conversationKey,
                    role = ChatTurn.Role.ASSISTANT.name,
                    content = replyText,
                    timestamp = now
                )
            )
        )
    }
}

package com.smsairelay.app

import android.content.Context

// Pure logic (no Android types) so it stays unit-testable.
object ConversationHistory {

    const val DEFAULT_IDLE_TIMEOUT_MINUTES = 45

    // Rough cap on how much history is sent per request. SMS turns are short, so this
    // still covers a long exchange; the point is to stop old turns steering new answers.
    const val HISTORY_TOKEN_BUDGET = 4000

    // Upper bound on rows kept per sender. Well above what the token budget ever sends,
    // so trimming the table never removes a turn the model would have seen.
    const val MAX_STORED_MESSAGES = 100

    const val RESET_REPLY = "New conversation started."

    private val RESET_COMMANDS = setOf("/new", "reset")

    fun isResetCommand(text: String): Boolean = text.trim().lowercase() in RESET_COMMANDS

    const val STOP_REPLY = "Stopped."
    const val NOTHING_TO_STOP_REPLY = "Nothing to stop."

    private val STOP_COMMANDS = setOf("/stop", "stop")

    fun isStopCommand(text: String): Boolean = text.trim().lowercase() in STOP_COMMANDS

    // Roughly three concatenated SMS segments (3 x 153 GSM-7 characters).
    const val SMS_CHUNK_CHARS = 450

    // Cuts a long reply into pieces that are sent one after another, so the sender can
    // stop the rest part-way. Prefers to break at whitespace; short text comes back whole.
    fun splitForSms(text: String, maxChars: Int = SMS_CHUNK_CHARS): List<String> {
        val chunks = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > maxChars) {
            val breakAt = rest.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
            chunks += rest.substring(0, breakAt).trimEnd()
            rest = rest.substring(breakAt).trimStart()
        }
        if (rest.isNotEmpty() || chunks.isEmpty()) chunks += rest
        return chunks
    }

    // Blank, zero, negative or non-numeric input falls back to the default.
    fun parseIdleTimeoutMinutes(raw: String): Int =
        raw.trim().toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_IDLE_TIMEOUT_MINUTES

    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    fun toTurns(stored: List<ChatMessage>): List<ChatTurn> =
        stored.map { ChatTurn(ChatTurn.Role.valueOf(it.role), it.content) }

    // Keeps the newest turns that fit the budget. The new message is always sent, even
    // if it alone exceeds the budget. Providers require the conversation to open with a
    // user turn, and trimming can cut an exchange in half, so leading assistant turns
    // are dropped.
    fun buildRequest(
        history: List<ChatTurn>,
        newMessage: String,
        tokenBudget: Int = HISTORY_TOKEN_BUDGET
    ): List<ChatTurn> {
        var remaining = tokenBudget - estimateTokens(newMessage)
        val kept = history.asReversed().takeWhile { turn ->
            remaining -= estimateTokens(turn.text)
            remaining >= 0
        }.asReversed()
        return (kept + ChatTurn(ChatTurn.Role.USER, newMessage))
            .dropWhile { it.role == ChatTurn.Role.ASSISTANT }
    }
}

// Takes the DAO directly so tests can pass an in-memory database.
class ConversationRepository(private val dao: ChatMessageDao) {

    constructor(context: Context) : this(ChatDatabase.get(context).messageDao())

    suspend fun load(conversationKey: String): List<ChatTurn> =
        ConversationHistory.toTurns(dao.recent(conversationKey, ConversationHistory.MAX_STORED_MESSAGES))

    suspend fun clear(conversationKey: String) = dao.deleteConversation(conversationKey)

    // Deletes every conversation idle longer than the timeout, not just this sender's,
    // so history from senders who never text again doesn't sit on the device forever.
    suspend fun forgetInactive(idleTimeoutMinutes: Int, now: Long = System.currentTimeMillis()) =
        dao.deleteInactiveBefore(now - idleTimeoutMinutes * 60_000L)

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
        dao.trim(conversationKey, ConversationHistory.MAX_STORED_MESSAGES)
    }
}

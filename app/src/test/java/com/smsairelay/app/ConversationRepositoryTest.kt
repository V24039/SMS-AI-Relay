package com.smsairelay.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Real Room/SQLite queries against an in-memory database.
@RunWith(AndroidJUnit4::class)
class ConversationRepositoryTest {

    private lateinit var db: ChatDatabase
    private lateinit var dao: ChatMessageDao
    private lateinit var repository: ConversationRepository

    private fun user(text: String) = ChatTurn(ChatTurn.Role.USER, text)
    private fun assistant(text: String) = ChatTurn(ChatTurn.Role.ASSISTANT, text)

    private fun message(key: String, role: ChatTurn.Role, text: String, timestamp: Long) =
        ChatMessage(conversationKey = key, role = role.name, content = text, timestamp = timestamp)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.messageDao()
        repository = ConversationRepository(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun loadOfUnknownConversationIsEmpty() = runBlocking {
        assertTrue(repository.load("nobody").isEmpty())
    }

    @Test
    fun saveExchangeStoresUserThenAssistantInOrder() = runBlocking {
        repository.saveExchange("a", "hi", "hello")
        repository.saveExchange("a", "weather?", "sunny")
        assertEquals(
            listOf(user("hi"), assistant("hello"), user("weather?"), assistant("sunny")),
            repository.load("a")
        )
    }

    @Test
    fun conversationsAreKeptSeparate() = runBlocking {
        repository.saveExchange("a", "from a", "reply a")
        repository.saveExchange("b", "from b", "reply b")
        assertEquals(listOf(user("from a"), assistant("reply a")), repository.load("a"))
        assertEquals(listOf(user("from b"), assistant("reply b")), repository.load("b"))
    }

    @Test
    fun clearDeletesOnlyThatConversation() = runBlocking {
        repository.saveExchange("a", "1", "2")
        repository.saveExchange("b", "3", "4")
        repository.clear("a")
        assertTrue(repository.load("a").isEmpty())
        assertEquals(2, repository.load("b").size)
    }

    @Test
    fun storageIsCappedAtMaxMessagesKeepingTheNewest() = runBlocking {
        val exchanges = ConversationHistory.MAX_STORED_MESSAGES / 2 + 10
        repeat(exchanges) { i -> repository.saveExchange("a", "q$i", "a$i") }

        val stored = dao.recent("a", Int.MAX_VALUE)
        assertEquals(ConversationHistory.MAX_STORED_MESSAGES, stored.size)
        assertEquals("q10", stored.first().content)
        assertEquals("a${exchanges - 1}", stored.last().content)
    }

    @Test
    fun trimOnlyAffectsTheSavedConversation() = runBlocking {
        val overCap = ConversationHistory.MAX_STORED_MESSAGES + 5
        dao.insertAll(List(overCap) { i -> message("b", ChatTurn.Role.USER, "b$i", 0) })
        repository.saveExchange("a", "x", "y")
        assertEquals(overCap, dao.recent("b", Int.MAX_VALUE).size)
    }

    @Test
    fun forgetInactiveDeletesConversationsIdlePastTimeout() = runBlocking {
        val now = 10_000_000L
        val minute = 60_000L
        // "old" last spoke 46 min ago, "recent" 44 min ago (it also has an older row).
        dao.insertAll(
            listOf(
                message("old", ChatTurn.Role.USER, "old", now - 46 * minute),
                message("recent", ChatTurn.Role.USER, "earlier", now - 90 * minute),
                message("recent", ChatTurn.Role.ASSISTANT, "latest", now - 44 * minute)
            )
        )

        repository.forgetInactive(idleTimeoutMinutes = 45, now = now)

        assertTrue(repository.load("old").isEmpty())
        // Judged by the newest message, so the whole conversation survives.
        assertEquals(2, repository.load("recent").size)
    }

    @Test
    fun conversationExactlyAtTimeoutIsKept() = runBlocking {
        val now = 10_000_000L
        dao.insertAll(listOf(message("edge", ChatTurn.Role.USER, "x", now - 45 * 60_000L)))
        repository.forgetInactive(idleTimeoutMinutes = 45, now = now)
        assertEquals(1, repository.load("edge").size)
    }

    @Test
    fun freshlySavedExchangeSurvivesForgetInactive() = runBlocking {
        repository.saveExchange("a", "hi", "hello")
        repository.forgetInactive(idleTimeoutMinutes = 1)
        assertEquals(2, repository.load("a").size)
    }
}

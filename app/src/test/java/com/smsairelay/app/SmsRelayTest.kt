package com.smsairelay.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Records every request and answers with the next scripted result.
class FakeAiClient(override val defaultModel: String = "fake-default") : AiClient {

    data class Call(val apiKey: String, val model: String, val systemPrompt: String, val turns: List<ChatTurn>)

    val calls = mutableListOf<Call>()
    var nextResult: (Call) -> Result<String> = { Result.success("reply ${calls.size}") }

    @Synchronized
    override fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<ChatTurn>
    ): Result<String> {
        val call = Call(apiKey, model, systemPrompt, turns)
        calls += call
        return nextResult(call)
    }
}

@RunWith(AndroidJUnit4::class)
class SmsRelayTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChatDatabase
    private lateinit var repository: ConversationRepository
    private val client = FakeAiClient()
    private val outbox = mutableListOf<Pair<String, String>>()

    private val originals = listOf(
        SmsRelay.repositoryFor, SmsRelay.clientFor, SmsRelay.sendSms, SettingsStore.secretKey
    )

    private val sender = "+91 98765 43210"

    private fun user(text: String) = ChatTurn(ChatTurn.Role.USER, text)
    private fun assistant(text: String) = ChatTurn(ChatTurn.Role.ASSISTANT, text)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        repository = ConversationRepository(db.messageDao())
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        SettingsStore.secretKey = { key }
        SmsRelay.repositoryFor = { repository }
        SmsRelay.clientFor = { client }
        SmsRelay.sendSms = { _, to, text -> synchronized(outbox) { outbox += to to text } }

        SettingsStore.setProvider(context, AiProvider.CLAUDE)
        SettingsStore.setApiKey(context, AiProvider.CLAUDE, "sk-ant-test")
    }

    @Suppress("UNCHECKED_CAST")
    @After
    fun tearDown() {
        SmsRelay.repositoryFor = originals[0] as (Context) -> ConversationRepository
        SmsRelay.clientFor = originals[1] as (AiProvider) -> AiClient
        SmsRelay.sendSms = originals[2] as (Context, String, String) -> Unit
        SettingsStore.secretKey = originals[3] as () -> javax.crypto.SecretKey
        SmsRelay.chunkDelayMs = 4_000L
        db.close()
    }

    private fun handle(body: String, from: String = sender) = runBlocking {
        SmsRelay.handle(context, from, body)
    }

    private val conversationKey = SenderAllowlist.conversationKey(sender)

    // --- Happy path ---

    @Test
    fun repliesWithModelAnswerToTheSender() {
        client.nextResult = { Result.success("It's sunny.") }
        handle("weather?")
        assertEquals(listOf(sender to "It's sunny."), outbox)
    }

    @Test
    fun sendsSystemPromptKeyAndDefaultModel() {
        handle("hi")
        val call = client.calls.single()
        assertEquals("sk-ant-test", call.apiKey)
        assertEquals(ClaudeClient.defaultModel, call.model)
        assertEquals(SMS_SYSTEM_PROMPT, call.systemPrompt)
        assertEquals(listOf(user("hi")), call.turns)
    }

    @Test
    fun sendsCustomSystemPromptWhenSet() {
        SettingsStore.setCustomSystemPrompt(context, "Answer like a pirate.")
        handle("hi")
        assertEquals("Answer like a pirate.", client.calls.single().systemPrompt)
    }

    @Test
    fun usesModelOverrideWhenSet() {
        SettingsStore.setModelOverride(context, AiProvider.CLAUDE, "claude-opus-5-5")
        handle("hi")
        assertEquals("claude-opus-5-5", client.calls.single().model)
    }

    @Test
    fun usesTheSelectedProvidersKey() {
        var asked: AiProvider? = null
        SmsRelay.clientFor = { asked = it; client }
        SettingsStore.setProvider(context, AiProvider.OPENAI)
        SettingsStore.setApiKey(context, AiProvider.OPENAI, "sk-openai")

        handle("hi")

        assertEquals(AiProvider.OPENAI, asked)
        assertEquals("sk-openai", client.calls.single().apiKey)
    }

    @Test
    fun savesExchangeAndSendsItAsHistoryNextTime() {
        client.nextResult = { Result.success("hello") }
        handle("hi")
        client.nextResult = { Result.success("fine") }
        handle("how are you?")

        assertEquals(
            listOf(user("hi"), assistant("hello"), user("how are you?")),
            client.calls[1].turns
        )
        assertEquals(
            listOf(user("hi"), assistant("hello"), user("how are you?"), assistant("fine")),
            runBlocking { repository.load(conversationKey) }
        )
    }

    @Test
    fun sameSenderInAnotherFormatContinuesTheConversation() {
        handle("hi", from = "+919876543210")
        handle("again", from = "09876543210")
        assertEquals(3, client.calls[1].turns.size)
    }

    // --- Reset keyword ---

    @Test
    fun resetKeywordClearsHistoryWithoutCallingTheAi() {
        handle("hi")
        handle("  /NEW ")

        assertEquals(1, client.calls.size)
        assertEquals(ConversationHistory.RESET_REPLY, outbox.last().second)
        assertTrue(runBlocking { repository.load(conversationKey) }.isEmpty())
    }

    @Test
    fun resetWorksEvenWithNoApiKey() {
        SettingsStore.setApiKey(context, AiProvider.CLAUDE, "")
        handle("RESET")
        assertEquals(ConversationHistory.RESET_REPLY, outbox.single().second)
    }

    @Test
    fun resetOnlyClearsTheSendersOwnHistory() {
        handle("hi", from = "+1 555 010 0100")
        handle("/new")
        assertEquals(
            2,
            runBlocking { repository.load(SenderAllowlist.conversationKey("+1 555 010 0100")) }.size
        )
    }

    // --- Stop command and chunked replies ---

    private val longReply = "word ".repeat(400).trim()

    @Test
    fun stopWithNothingRunningRepliesWithoutCallingTheAi() {
        handle(" /Stop ")
        assertTrue(client.calls.isEmpty())
        assertEquals(ConversationHistory.NOTHING_TO_STOP_REPLY, outbox.single().second)
    }

    @Test
    fun longReplyIsSentInSeveralTexts() {
        SmsRelay.chunkDelayMs = 0
        client.nextResult = { Result.success(longReply) }
        handle("hi")

        assertTrue(outbox.size > 1)
        assertEquals(longReply, outbox.joinToString(" ") { it.second })
    }

    @Test
    fun stopCancelsTheRestOfALongReply() = runBlocking {
        SmsRelay.chunkDelayMs = 60_000
        client.nextResult = { Result.success(longReply) }

        val job = launch(Dispatchers.Default) { SmsRelay.handle(context, sender, "hi") }
        withTimeout(10_000) { while (synchronized(outbox) { outbox.isEmpty() }) delay(10) }
        SmsRelay.handle(context, sender, "STOP")
        withTimeout(10_000) { job.join() }

        assertEquals(2, outbox.size)
        assertEquals(ConversationHistory.STOP_REPLY, outbox.last().second)
    }

    // --- Failures ---

    @Test
    fun missingApiKeyRepliesWithSetupHintAndSkipsTheAi() {
        SettingsStore.setApiKey(context, AiProvider.CLAUDE, "")
        handle("hi")

        assertTrue(client.calls.isEmpty())
        val reply = outbox.single().second
        assertTrue(reply.contains("no Claude (Anthropic) API key set"))
    }

    @Test
    fun failedAiCallRepliesWithApologyAndSavesNothing() {
        client.nextResult = { Result.failure(IOException("HTTP 500")) }
        handle("hi")

        assertTrue(outbox.single().second.startsWith("Sorry, something went wrong"))
        assertTrue(runBlocking { repository.load(conversationKey) }.isEmpty())
    }

    @Test
    fun unexpectedExceptionIsSwallowedAndNothingIsSent() {
        client.nextResult = { throw IllegalStateException("bug") }
        handle("hi")
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun failedSendIsSwallowedButExchangeIsAlreadySaved() {
        SmsRelay.sendSms = { _, _, _ -> throw IllegalArgumentException("no SIM") }
        handle("hi")
        assertEquals(2, runBlocking { repository.load(conversationKey) }.size)
    }

    // --- Reset policy applied before the request ---

    @Test
    fun idleConversationIsForgottenBeforeTheRequest() = runBlocking {
        db.messageDao().insertAll(
            listOf(
                ChatMessage(
                    conversationKey = conversationKey,
                    role = "USER",
                    content = "ancient",
                    timestamp = System.currentTimeMillis() - 2 * 60 * 60_000L
                )
            )
        )
        handle("hi")
        assertEquals(listOf(user("hi")), client.calls.single().turns)
    }

    @Test
    fun conversationWithinTimeoutIsKept() {
        SettingsStore.setIdleTimeoutMinutes(context, 45)
        handle("hi")
        handle("again")
        assertEquals(3, client.calls[1].turns.size)
    }

    // --- Serialisation ---

    // With the lock, concurrent messages from one sender are handled one after another,
    // so each request sees the previous exchange and history ends up complete.
    @Test
    fun concurrentMessagesAreHandledOneAtATime() {
        runBlocking {
            (1..5).map { i ->
                async(kotlinx.coroutines.Dispatchers.IO) { SmsRelay.handle(context, sender, "m$i") }
            }.awaitAll()
        }

        assertEquals(5, outbox.size)
        assertEquals(listOf(1, 3, 5, 7, 9), client.calls.map { it.turns.size })
        assertEquals(10, runBlocking { repository.load(conversationKey) }.size)
    }
}

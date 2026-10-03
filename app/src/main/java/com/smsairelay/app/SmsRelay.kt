package com.smsairelay.app

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The SMS-in → AI → SMS-out pipeline. Runs inside RelayService normally, or inside the
// receiver's goAsync() window when the service can't be started.
object SmsRelay {

    private const val TAG = "SmsRelay"

    // One message at a time, process-wide: two texts from the same sender arriving
    // together would otherwise both read the same history and save out of order.
    private val lock = Mutex()

    // Replaced in tests so the pipeline runs against an in-memory database, a fake AI
    // client and a captured outbox instead of real network calls and real texts.
    internal var repositoryFor: (Context) -> ConversationRepository = { ConversationRepository(it) }
    internal var clientFor: (AiProvider) -> AiClient = { it.client }
    internal var sendSms: (Context, String, String) -> Unit = ::sendReply

    // Pause between pieces of a long reply: gives the sender time to text STOP, and
    // keeps the pieces arriving in order.
    internal var chunkDelayMs = 4_000L

    // Messages of each sender that are queued, being answered or being sent. A stop
    // command cancels them; it never takes `lock`, since the send loop holds it.
    private val inFlight = mutableListOf<Pair<String, Job>>()

    suspend fun handle(context: Context, sender: String, body: String) {
        val key = SenderAllowlist.conversationKey(sender)
        var registered: Pair<String, Job>? = null
        try {
            if (ConversationHistory.isStopCommand(body)) {
                val stopped = cancelInFlight(key)
                sendSms(
                    context,
                    sender,
                    if (stopped) ConversationHistory.STOP_REPLY else ConversationHistory.NOTHING_TO_STOP_REPLY
                )
                return
            }
            currentCoroutineContext()[Job]?.let { job ->
                registered = key to job
                synchronized(inFlight) { inFlight += key to job }
            }
            lock.withLock {
                val reply = buildReply(context, key, body)
                val chunks = ConversationHistory.splitForSms(reply)
                chunks.forEachIndexed { i, chunk ->
                    if (i > 0) delay(chunkDelayMs)
                    currentCoroutineContext().ensureActive()
                    sendSms(context, sender, chunk)
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Reply stopped by the sender")
            throw e
        } catch (e: Exception) {
            // An uncaught exception here would crash the whole process.
            Log.e(TAG, "Failed to handle incoming SMS", e)
        } finally {
            registered?.let { entry -> synchronized(inFlight) { inFlight.remove(entry) } }
        }
    }

    // True if anything was queued, being answered or being sent for this sender.
    private fun cancelInFlight(key: String): Boolean {
        val jobs = synchronized(inFlight) { inFlight.filter { it.first == key }.map { it.second } }
        jobs.forEach { it.cancel() }
        return jobs.isNotEmpty()
    }

    private suspend fun buildReply(context: Context, conversationKey: String, userMessage: String): String {
        val repository = repositoryFor(context)
        if (ConversationHistory.isResetCommand(userMessage)) {
            repository.clear(conversationKey)
            return ConversationHistory.RESET_REPLY
        }

        val provider = SettingsStore.getProvider(context)
        val apiKey = SettingsStore.getApiKey(context, provider)
            ?: return "SMS AI Relay: no ${provider.displayName} API key set yet. " +
                "Open the app and add one in Settings."

        repository.forgetInactive(SettingsStore.getIdleTimeoutMinutes(context))
        val turns = ConversationHistory.buildRequest(repository.load(conversationKey), userMessage)

        return clientFor(provider).complete(
            apiKey = apiKey,
            model = SettingsStore.getModel(context, provider),
            systemPrompt = SettingsStore.getSystemPrompt(context),
            turns = turns
        ).fold(
            onSuccess = { reply ->
                repository.saveExchange(conversationKey, userMessage, reply)
                reply
            },
            onFailure = {
                Log.e(TAG, "${provider.name} API call failed", it)
                "Sorry, something went wrong reaching the AI. Try again in a bit."
            }
        )
    }

    private fun sendReply(context: Context, destination: String, text: String) {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = smsManager.divideMessage(text)
        smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
    }
}

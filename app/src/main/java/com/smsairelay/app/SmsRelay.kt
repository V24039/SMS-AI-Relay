package com.smsairelay.app

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The SMS-in → AI → SMS-out pipeline. Runs inside RelayService normally, or inside the
// receiver's goAsync() window when the service can't be started.
object SmsRelay {

    private const val TAG = "SmsRelay"

    // One message at a time, process-wide: two texts from the same sender arriving
    // together would otherwise both read the same history and save out of order.
    private val lock = Mutex()

    suspend fun handle(context: Context, sender: String, body: String) {
        try {
            lock.withLock {
                val reply = buildReply(context, SenderAllowlist.conversationKey(sender), body)
                sendReply(context, sender, reply)
            }
        } catch (e: Exception) {
            // An uncaught exception here would crash the whole process.
            Log.e(TAG, "Failed to handle incoming SMS", e)
        }
    }

    private suspend fun buildReply(context: Context, conversationKey: String, userMessage: String): String {
        val repository = ConversationRepository(context)
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

        return provider.client.complete(
            apiKey = apiKey,
            model = SettingsStore.getModel(context, provider),
            systemPrompt = SMS_SYSTEM_PROMPT,
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

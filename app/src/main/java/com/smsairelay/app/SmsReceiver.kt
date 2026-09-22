package com.smsairelay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        Log.i(TAG, "SMS from $sender: $body")

        // The Claude API call is network I/O, so it can't run inline on onReceive's
        // main thread — goAsync() buys the ~10s window a manifest receiver needs to
        // hand off to a background coroutine without the OS killing it mid-flight.
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reply = buildReply(appContext, body)
                sendReply(appContext, sender, reply)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildReply(context: Context, userMessage: String): String {
        val apiKey = SettingsStore.getApiKey(context)
            ?: return "SMS AI Relay: no API key set yet. Open the app and add one in Settings."

        return ClaudeApiClient.sendMessage(apiKey, userMessage).fold(
            onSuccess = { it },
            onFailure = {
                Log.e(TAG, "Claude API call failed", it)
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

    companion object {
        private const val TAG = "SmsReceiver"
    }
}

package com.smsairelay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
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

        // Checked before anything else so unknown senders (spam, shortcodes, other bots)
        // never cost an API call or an outgoing SMS. Empty allowlist = reply to nobody.
        if (!SenderAllowlist.isAllowed(sender, SettingsStore.getAllowlist(context))) {
            Log.i(TAG, "Dropped SMS from non-allowlisted sender")
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "SMS from $sender: $body")

        // Normal path: the foreground service does the work, so a slow AI call
        // isn't cut off and the process isn't killed mid-flight.
        if (RelayService.enqueue(context, sender, body)) return

        // Fallback when Android refuses to start the service from the background
        // (Android 12+ without the battery-optimization exemption): handle it here
        // within the ~10s goAsync() window, as before Phase 6.
        Log.w(TAG, "Relay service unavailable; handling SMS in the receiver")
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsRelay.handle(appContext, sender, body)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}

package com.smsairelay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Long-running foreground service that keeps the relay process alive and out of
// Doze's network block, and runs each incoming SMS through SmsRelay.
// Started from MainActivity, on boot / app update (BootReceiver), and by SmsReceiver.
class RelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground();
        // repeating it while already in the foreground is harmless.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )

        val sender = intent?.getStringExtra(EXTRA_SENDER)
        val body = intent?.getStringExtra(EXTRA_BODY)
        if (sender != null && body != null) {
            scope.launch { SmsRelay.handle(applicationContext, sender, body) }
        }

        // Restarted (with a null intent) if the system kills it; a message that was
        // mid-flight at that moment is lost and the sender has to text again.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.relay_channel_name),
                // LOW rather than MIN: MIN foreground notifications get replaced by
                // a louder system "running in the background" notice on some versions.
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(getString(R.string.relay_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "relay_status"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_BODY = "body"

        // Starts the service with no message, just to have it running.
        fun start(context: Context): Boolean =
            startService(context, Intent(context, RelayService::class.java))

        // Hands a message to the service. False if Android refused to start it — on
        // Android 12+ a background start needs the battery-optimization exemption
        // (or the service already running), so the caller must handle it itself.
        fun enqueue(context: Context, sender: String, body: String): Boolean =
            startService(
                context,
                Intent(context, RelayService::class.java)
                    .putExtra(EXTRA_SENDER, sender)
                    .putExtra(EXTRA_BODY, body)
            )

        private fun startService(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (API 31+).
            Log.w(TAG, "Not allowed to start relay service", e)
            false
        }
    }
}

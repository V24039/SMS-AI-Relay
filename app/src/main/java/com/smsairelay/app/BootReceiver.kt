package com.smsairelay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Re-arms the relay service after a reboot or an app update. Both broadcasts are on
// Android's list of exemptions that may start a foreground service from the background.
// BOOT_COMPLETED only arrives once the app has been opened at least once after install.
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> RelayService.start(context)
        }
    }
}

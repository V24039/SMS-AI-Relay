package com.smsairelay.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun launch(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun setBatteryExempt(exempt: Boolean) {
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, exempt)
    }

    private fun grantSmsPermissions() {
        shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
    }

    private fun MainActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun MainActivity.button(id: Int) = findViewById<Button>(id)

    // --- SMS permissions ---

    @Test
    fun withoutPermissionsShowsPromptAndDoesNotStartService() {
        val activity = launch()
        assertEquals(app.getString(R.string.permissions_needed), activity.text(R.id.statusText))
        assertTrue(activity.button(R.id.grantButton).isEnabled)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun withPermissionsShowsListeningAndStartsRelayService() {
        grantSmsPermissions()
        val activity = launch()

        assertEquals(app.getString(R.string.permissions_granted), activity.text(R.id.statusText))
        assertFalse(activity.button(R.id.grantButton).isEnabled)
        assertEquals(
            RelayService::class.java.name,
            shadowOf(app).nextStartedService.component!!.className
        )
    }

    // --- Battery optimization ---

    @Test
    fun batteryOptimizedShowsWarningAndEnabledButton() {
        setBatteryExempt(false)
        val activity = launch()
        assertEquals(app.getString(R.string.battery_restricted), activity.text(R.id.batteryText))
        assertTrue(activity.button(R.id.batteryButton).isEnabled)
    }

    @Test
    fun batteryExemptShowsOkAndDisablesButton() {
        setBatteryExempt(true)
        val activity = launch()
        assertEquals(app.getString(R.string.battery_unrestricted), activity.text(R.id.batteryText))
        assertFalse(activity.button(R.id.batteryButton).isEnabled)
    }

    // Status is re-checked when the user comes back from the system prompt.
    @Test
    fun batteryStatusRefreshesOnResume() {
        setBatteryExempt(false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        setBatteryExempt(true)
        controller.pause().resume()

        assertFalse(controller.get().button(R.id.batteryButton).isEnabled)
    }

    @Test
    fun batteryButtonOpensDirectExemptionPromptForThisApp() {
        setBatteryExempt(false)
        val activity = launch()

        activity.button(R.id.batteryButton).performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, started.action)
        assertEquals(Uri.parse("package:${app.packageName}"), started.data)
    }

    @Test
    fun batteryButtonFallsBackToSettingsListWhenPromptIsMissing() {
        setBatteryExempt(false)
        // Only the fallback screen exists, so the direct prompt throws ActivityNotFoundException.
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        shadowOf(app.packageManager).addResolveInfoForIntent(
            fallback,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.settings"
                    name = "BatteryOptimizationSettings"
                }
            }
        )
        shadowOf(app).checkActivities(true)
        val activity = launch()

        activity.button(R.id.batteryButton).performClick()

        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            shadowOf(activity).nextStartedActivity.action
        )
    }

    @Test
    fun settingsButtonOpensSettingsScreen() {
        val activity = launch()
        activity.button(R.id.settingsButton).performClick()
        assertEquals(
            SettingsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className
        )
    }
}

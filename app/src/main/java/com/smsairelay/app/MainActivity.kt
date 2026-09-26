package com.smsairelay.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smsairelay.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        updateStatus(result.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantButton.setOnClickListener {
            permissionLauncher.launch(requiredPermissions)
        }

        binding.batteryButton.setOnClickListener { requestBatteryExemption() }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateStatus(hasAllPermissions())
    }

    override fun onResume() {
        super.onResume()
        updateStatus(hasAllPermissions())
        updateBatteryStatus()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatus(granted: Boolean) {
        binding.statusText.text = getString(
            if (granted) R.string.permissions_granted else R.string.permissions_needed
        )
        binding.grantButton.isEnabled = !granted
        // Started from here because the activity is in the foreground, which Android
        // always allows; after that the service keeps itself (and the relay) alive.
        if (granted) RelayService.start(this)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun updateBatteryStatus() {
        val exempt = isIgnoringBatteryOptimizations()
        binding.batteryText.text = getString(
            if (exempt) R.string.battery_unrestricted else R.string.battery_restricted
        )
        binding.batteryButton.isEnabled = !exempt
    }

    // The exemption keeps network access during Doze and, on Android 12+, lets the SMS
    // receiver start the relay service from the background.
    @SuppressLint("BatteryLife") // Sideload-only, so the Play policy this lint guards doesn't apply.
    private fun requestBatteryExemption() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            // Some OEM builds drop the direct prompt; fall back to the full list.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

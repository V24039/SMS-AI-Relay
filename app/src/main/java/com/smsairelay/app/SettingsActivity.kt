package com.smsairelay.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.smsairelay.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apiKeyInput.setText(SettingsStore.getApiKey(this).orEmpty())
        binding.allowlistInput.setText(SettingsStore.getAllowlistRaw(this))

        binding.saveButton.setOnClickListener {
            SettingsStore.setApiKey(this, binding.apiKeyInput.text.toString())
            SettingsStore.setAllowlist(this, binding.allowlistInput.text.toString())
            binding.savedNote.visibility = View.VISIBLE
        }
    }
}

package com.smsairelay.app

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.smsairelay.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // Edits are kept while switching the dropdown, so typing a Gemini key and then
    // looking at the OpenAI fields doesn't lose it before Save. Keys are held per
    // keySlot because some providers (Gemini/Gemma) share one.
    private val keyDrafts = mutableMapOf<String, String>()
    private val modelDrafts = mutableMapOf<AiProvider, String>()
    private lateinit var shownProvider: AiProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val providers = AiProvider.entries
        providers.forEach { provider ->
            keyDrafts[provider.keySlot] = SettingsStore.getApiKey(this, provider).orEmpty()
            modelDrafts[provider] = SettingsStore.getModelOverride(this, provider).orEmpty()
        }
        shownProvider = SettingsStore.getProvider(this)
        showDraft(shownProvider)

        binding.providerSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, providers
        )
        binding.providerSpinner.setSelection(providers.indexOf(shownProvider))
        binding.providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = providers[position]
                if (selected == shownProvider) return
                stashDraft()
                shownProvider = selected
                showDraft(selected)
                binding.savedNote.visibility = View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.allowlistInput.setText(SettingsStore.getAllowlistRaw(this))

        binding.saveButton.setOnClickListener {
            stashDraft()
            providers.forEach { provider ->
                SettingsStore.setApiKey(this, provider, keyDrafts.getValue(provider.keySlot))
                SettingsStore.setModelOverride(this, provider, modelDrafts.getValue(provider))
            }
            SettingsStore.setProvider(this, shownProvider)
            SettingsStore.setAllowlist(this, binding.allowlistInput.text.toString())
            binding.savedNote.visibility = View.VISIBLE
        }
    }

    private fun stashDraft() {
        keyDrafts[shownProvider.keySlot] = binding.apiKeyInput.text.toString()
        modelDrafts[shownProvider] = binding.modelInput.text.toString()
    }

    private fun showDraft(provider: AiProvider) {
        binding.apiKeyInput.hint = provider.keyHint
        binding.apiKeyInput.setText(keyDrafts.getValue(provider.keySlot))
        binding.modelInput.hint = provider.client.defaultModel
        binding.modelInput.setText(modelDrafts.getValue(provider))
    }
}

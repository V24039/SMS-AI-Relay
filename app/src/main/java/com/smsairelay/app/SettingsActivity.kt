package com.smsairelay.app

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.smsairelay.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // Edits for each provider are kept while switching the dropdown, so typing a
    // Gemini key and then looking at the OpenAI fields doesn't lose it before Save.
    private data class Draft(var apiKey: String, var model: String)

    private val drafts = mutableMapOf<AiProvider, Draft>()
    private lateinit var shownProvider: AiProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val providers = AiProvider.entries
        AiProvider.entries.forEach { provider ->
            drafts[provider] = Draft(
                SettingsStore.getApiKey(this, provider).orEmpty(),
                SettingsStore.getModelOverride(this, provider).orEmpty()
            )
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
            drafts.forEach { (provider, draft) ->
                SettingsStore.setApiKey(this, provider, draft.apiKey)
                SettingsStore.setModelOverride(this, provider, draft.model)
            }
            SettingsStore.setProvider(this, shownProvider)
            SettingsStore.setAllowlist(this, binding.allowlistInput.text.toString())
            binding.savedNote.visibility = View.VISIBLE
        }
    }

    private fun stashDraft() {
        drafts.getValue(shownProvider).apply {
            apiKey = binding.apiKeyInput.text.toString()
            model = binding.modelInput.text.toString()
        }
    }

    private fun showDraft(provider: AiProvider) {
        val draft = drafts.getValue(provider)
        binding.apiKeyInput.hint = provider.keyHint
        binding.apiKeyInput.setText(draft.apiKey)
        binding.modelInput.hint = provider.client.defaultModel
        binding.modelInput.setText(draft.model)
    }
}

package com.smsairelay.app

import android.content.Context
import android.content.SharedPreferences

// Plaintext SharedPreferences for now — Phase 5 moves this to EncryptedSharedPreferences.
object SettingsStore {

    private const val PREFS_NAME = "relay_settings"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ALLOWLIST = "sender_allowlist"

    // Single key from before multi-provider support; it was always a Claude key.
    private const val KEY_LEGACY_API_KEY = "api_key"

    private fun apiKeyPref(provider: AiProvider) = "api_key_${provider.keySlot}"
    private fun modelPref(provider: AiProvider) = "model_${provider.name}"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProvider(context: Context): AiProvider {
        val name = prefs(context).getString(KEY_PROVIDER, null)
        return AiProvider.entries.firstOrNull { it.name == name } ?: AiProvider.CLAUDE
    }

    fun setProvider(context: Context, provider: AiProvider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun getApiKey(context: Context, provider: AiProvider): String? {
        val prefs = prefs(context)
        val key = prefs.getString(apiKeyPref(provider), null)
            ?: if (provider == AiProvider.CLAUDE) prefs.getString(KEY_LEGACY_API_KEY, null) else null
        return key?.takeIf { it.isNotBlank() }
    }

    fun setApiKey(context: Context, provider: AiProvider, apiKey: String) {
        val editor = prefs(context).edit().putString(apiKeyPref(provider), apiKey.trim())
        if (provider == AiProvider.CLAUDE) editor.remove(KEY_LEGACY_API_KEY)
        editor.apply()
    }

    // The model override the user typed, or null to use the provider's default.
    fun getModelOverride(context: Context, provider: AiProvider): String? =
        prefs(context).getString(modelPref(provider), null)?.takeIf { it.isNotBlank() }

    fun getModel(context: Context, provider: AiProvider): String =
        getModelOverride(context, provider) ?: provider.client.defaultModel

    fun setModelOverride(context: Context, provider: AiProvider, model: String) {
        prefs(context).edit().putString(modelPref(provider), model.trim()).apply()
    }

    fun getAllowlistRaw(context: Context): String =
        prefs(context).getString(KEY_ALLOWLIST, null).orEmpty()

    fun getAllowlist(context: Context): List<String> =
        SenderAllowlist.parse(getAllowlistRaw(context))

    fun setAllowlist(context: Context, raw: String) {
        prefs(context).edit().putString(KEY_ALLOWLIST, raw.trim()).apply()
    }
}

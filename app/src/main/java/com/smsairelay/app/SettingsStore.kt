package com.smsairelay.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

// API keys are encrypted with a Keystore-held AES key (SecretCipher); the other
// settings aren't secret and stay in plain SharedPreferences.
object SettingsStore {

    private const val TAG = "SettingsStore"

    private const val PREFS_NAME = "relay_settings"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ALLOWLIST = "sender_allowlist"
    private const val KEY_IDLE_TIMEOUT_MINUTES = "idle_timeout_minutes"

    // Single key from before multi-provider support; it was always a Claude key.
    private const val KEY_LEGACY_API_KEY = "api_key"

    private fun encryptedApiKeyPref(provider: AiProvider) = "enc_api_key_${provider.keySlot}"

    // Where keys were stored in plaintext before Phase 5; only read to migrate them.
    private fun plaintextApiKeyPref(provider: AiProvider) = "api_key_${provider.keySlot}"
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
        prefs.getString(encryptedApiKeyPref(provider), null)?.let { encrypted ->
            val key = SecretCipher.decrypt(KeystoreSecretKey.get(), encrypted)
            // Only happens if the Keystore key was lost (e.g. device credential reset);
            // the user has to re-enter the key in Settings.
            if (key == null) Log.w(TAG, "Stored ${provider.name} API key could not be decrypted")
            return key?.takeIf { it.isNotBlank() }
        }

        // Pre-Phase-5 plaintext key: encrypt it now so it stops sitting on disk in the clear.
        val plaintext = prefs.getString(plaintextApiKeyPref(provider), null)
            ?: if (provider == AiProvider.CLAUDE) prefs.getString(KEY_LEGACY_API_KEY, null) else null
        if (plaintext != null) setApiKey(context, provider, plaintext)
        return plaintext?.trim()?.takeIf { it.isNotBlank() }
    }

    fun setApiKey(context: Context, provider: AiProvider, apiKey: String) {
        val trimmed = apiKey.trim()
        val editor = prefs(context).edit().remove(plaintextApiKeyPref(provider))
        if (trimmed.isEmpty()) {
            editor.remove(encryptedApiKeyPref(provider))
        } else {
            editor.putString(
                encryptedApiKeyPref(provider),
                SecretCipher.encrypt(KeystoreSecretKey.get(), trimmed)
            )
        }
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

    fun getIdleTimeoutMinutes(context: Context): Int =
        prefs(context).getInt(KEY_IDLE_TIMEOUT_MINUTES, ConversationHistory.DEFAULT_IDLE_TIMEOUT_MINUTES)

    fun setIdleTimeoutMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_IDLE_TIMEOUT_MINUTES, minutes).apply()
    }
}

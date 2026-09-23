package com.smsairelay.app

import android.content.Context

// Plaintext SharedPreferences for now — Phase 5 moves this to EncryptedSharedPreferences.
object SettingsStore {

    private const val PREFS_NAME = "relay_settings"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_ALLOWLIST = "sender_allowlist"

    fun getApiKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun setApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    fun getAllowlistRaw(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ALLOWLIST, null).orEmpty()

    fun getAllowlist(context: Context): List<String> =
        SenderAllowlist.parse(getAllowlistRaw(context))

    fun setAllowlist(context: Context, raw: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALLOWLIST, raw.trim())
            .apply()
    }
}

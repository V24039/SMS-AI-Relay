package com.smsairelay.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rawPrefs get() = context.getSharedPreferences("relay_settings", Context.MODE_PRIVATE)
    private val originalKey = SettingsStore.secretKey

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        // Robolectric has no Android Keystore; a software key exercises the same cipher.
        val key = newKey()
        SettingsStore.secretKey = { key }
    }

    @After
    fun tearDown() {
        SettingsStore.secretKey = originalKey
    }

    // --- Provider ---

    @Test
    fun providerDefaultsToClaude() {
        assertEquals(AiProvider.CLAUDE, SettingsStore.getProvider(context))
    }

    @Test
    fun providerRoundTrips() {
        SettingsStore.setProvider(context, AiProvider.GEMMA)
        assertEquals(AiProvider.GEMMA, SettingsStore.getProvider(context))
    }

    @Test
    fun unknownStoredProviderFallsBackToClaude() {
        rawPrefs.edit().putString("provider", "REMOVED_PROVIDER").commit()
        assertEquals(AiProvider.CLAUDE, SettingsStore.getProvider(context))
    }

    // --- API keys ---

    @Test
    fun apiKeyIsNullWhenNeverSet() {
        AiProvider.entries.forEach { assertNull(SettingsStore.getApiKey(context, it)) }
    }

    @Test
    fun apiKeyRoundTripsTrimmed() {
        SettingsStore.setApiKey(context, AiProvider.OPENAI, "  sk-abc  ")
        assertEquals("sk-abc", SettingsStore.getApiKey(context, AiProvider.OPENAI))
    }

    @Test
    fun apiKeyIsNeverStoredInPlaintext() {
        SettingsStore.setApiKey(context, AiProvider.CLAUDE, "sk-ant-secret-value")
        val stored = rawPrefs.all.values.joinToString(" ")
        assertFalse(stored.contains("sk-ant-secret-value"))
        assertNotNull(rawPrefs.getString("enc_api_key_CLAUDE", null))
    }

    @Test
    fun blankApiKeyRemovesTheStoredKey() {
        SettingsStore.setApiKey(context, AiProvider.CLAUDE, "sk-ant-x")
        SettingsStore.setApiKey(context, AiProvider.CLAUDE, "   ")
        assertNull(SettingsStore.getApiKey(context, AiProvider.CLAUDE))
        assertFalse(rawPrefs.contains("enc_api_key_CLAUDE"))
    }

    @Test
    fun keysAreSeparatePerProvider() {
        SettingsStore.setApiKey(context, AiProvider.CLAUDE, "claude-key")
        SettingsStore.setApiKey(context, AiProvider.OPENAI, "openai-key")
        assertEquals("claude-key", SettingsStore.getApiKey(context, AiProvider.CLAUDE))
        assertEquals("openai-key", SettingsStore.getApiKey(context, AiProvider.OPENAI))
        assertNull(SettingsStore.getApiKey(context, AiProvider.GEMINI))
    }

    @Test
    fun geminiAndGemmaShareOneStoredKey() {
        SettingsStore.setApiKey(context, AiProvider.GEMMA, "AIza-shared")
        assertEquals("AIza-shared", SettingsStore.getApiKey(context, AiProvider.GEMINI))
    }

    // Keystore key lost (e.g. device credential reset): user must re-enter, no crash.
    @Test
    fun keyEncryptedUnderAnotherKeyReadsAsNull() {
        SettingsStore.setApiKey(context, AiProvider.CLAUDE, "sk-ant-x")
        val otherKey = newKey()
        SettingsStore.secretKey = { otherKey }
        assertNull(SettingsStore.getApiKey(context, AiProvider.CLAUDE))
    }

    // --- Migration of pre-Phase-5 plaintext keys ---

    @Test
    fun plaintextKeyIsMigratedToEncryptedOnFirstRead() {
        rawPrefs.edit().putString("api_key_OPENAI", " sk-old ").commit()

        assertEquals("sk-old", SettingsStore.getApiKey(context, AiProvider.OPENAI))

        assertFalse(rawPrefs.contains("api_key_OPENAI"))
        assertTrue(rawPrefs.contains("enc_api_key_OPENAI"))
        // Second read comes from the encrypted copy.
        assertEquals("sk-old", SettingsStore.getApiKey(context, AiProvider.OPENAI))
    }

    @Test
    fun legacySingleKeyIsMigratedAsClaudeKey() {
        rawPrefs.edit().putString("api_key", "sk-ant-legacy").commit()

        assertEquals("sk-ant-legacy", SettingsStore.getApiKey(context, AiProvider.CLAUDE))

        assertFalse(rawPrefs.contains("api_key"))
        assertTrue(rawPrefs.contains("enc_api_key_CLAUDE"))
    }

    @Test
    fun legacySingleKeyIsNotUsedForOtherProviders() {
        rawPrefs.edit().putString("api_key", "sk-ant-legacy").commit()
        assertNull(SettingsStore.getApiKey(context, AiProvider.OPENAI))
        assertTrue(rawPrefs.contains("api_key"))
    }

    @Test
    fun perProviderPlaintextKeyWinsOverLegacyKey() {
        rawPrefs.edit()
            .putString("api_key", "sk-ant-legacy")
            .putString("api_key_CLAUDE", "sk-ant-newer")
            .commit()
        assertEquals("sk-ant-newer", SettingsStore.getApiKey(context, AiProvider.CLAUDE))
        assertFalse(rawPrefs.contains("api_key"))
    }

    @Test
    fun blankPlaintextKeyMigratesToNothing() {
        rawPrefs.edit().putString("api_key_OPENAI", "  ").commit()
        assertNull(SettingsStore.getApiKey(context, AiProvider.OPENAI))
        assertFalse(rawPrefs.contains("api_key_OPENAI"))
        assertFalse(rawPrefs.contains("enc_api_key_OPENAI"))
    }

    // --- Model override ---

    @Test
    fun modelFallsBackToProviderDefault() {
        assertNull(SettingsStore.getModelOverride(context, AiProvider.CLAUDE))
        assertEquals(ClaudeClient.defaultModel, SettingsStore.getModel(context, AiProvider.CLAUDE))
    }

    @Test
    fun modelOverrideIsTrimmedAndBlankMeansDefault() {
        SettingsStore.setModelOverride(context, AiProvider.CLAUDE, " claude-opus-5-5 ")
        assertEquals("claude-opus-5-5", SettingsStore.getModel(context, AiProvider.CLAUDE))

        SettingsStore.setModelOverride(context, AiProvider.CLAUDE, "")
        assertEquals(ClaudeClient.defaultModel, SettingsStore.getModel(context, AiProvider.CLAUDE))
    }

    // Unlike keys, Gemini and Gemma keep separate model overrides.
    @Test
    fun modelOverrideIsPerProviderEvenWhenKeyIsShared() {
        SettingsStore.setModelOverride(context, AiProvider.GEMINI, "gemini-custom")
        assertNull(SettingsStore.getModelOverride(context, AiProvider.GEMMA))
    }

    // --- Allowlist and idle timeout ---

    @Test
    fun allowlistIsStoredTrimmedAndParsed() {
        SettingsStore.setAllowlist(context, "\n+91 98765 43210\n+1 555 0100\n")
        assertEquals("+91 98765 43210\n+1 555 0100", SettingsStore.getAllowlistRaw(context))
        assertEquals(
            listOf("+91 98765 43210", "+1 555 0100"),
            SettingsStore.getAllowlist(context)
        )
    }

    @Test
    fun allowlistIsEmptyByDefault() {
        assertEquals("", SettingsStore.getAllowlistRaw(context))
        assertTrue(SettingsStore.getAllowlist(context).isEmpty())
    }

    @Test
    fun idleTimeoutDefaultsAndRoundTrips() {
        assertEquals(
            ConversationHistory.DEFAULT_IDLE_TIMEOUT_MINUTES,
            SettingsStore.getIdleTimeoutMinutes(context)
        )
        SettingsStore.setIdleTimeoutMinutes(context, 10)
        assertEquals(10, SettingsStore.getIdleTimeoutMinutes(context))
    }
}

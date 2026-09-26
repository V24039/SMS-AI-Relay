package com.smsairelay.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// AES-GCM encryption of short secrets (API keys) for storage in SharedPreferences.
// Kept free of Android types so it can be tested on the JVM with a software key.
// Stored format: base64(12-byte IV || ciphertext+tag).
object SecretCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, plaintext: String): String {
        // No IV passed: the provider generates a fresh random one, which the
        // Android Keystore requires (it refuses caller-supplied IVs by default).
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected GCM IV length ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    // Null if the value is malformed, was tampered with, or was encrypted under a different key.
    fun decrypt(key: SecretKey, encoded: String): String? = try {
        val bytes = Base64.getDecoder().decode(encoded)
        if (bytes.size <= IV_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES)
            )
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: GeneralSecurityException) {
        null
    }
}

// The AES key lives in the Android Keystore: it's non-exportable, so the encrypted
// prefs file is useless if copied off the device (e.g. from a rooted backup).
object KeystoreSecretKey {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "relay_api_keys"

    @Volatile
    private var cached: SecretKey? = null

    // Synchronized so the receiver and Settings screen can't both generate a key on first use.
    @Synchronized
    fun get(): SecretKey {
        cached?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: generate()
        cached = key
        return key
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

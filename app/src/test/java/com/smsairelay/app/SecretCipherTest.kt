package com.smsairelay.app

import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Uses a software AES key; on device the same code runs with a Keystore-held key.
class SecretCipherTest {

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()

    @Test
    fun roundTripsSecret() {
        val secret = "sk-ant-api03-abc_DEF-123"
        assertEquals(secret, SecretCipher.decrypt(key, SecretCipher.encrypt(key, secret)))
    }

    @Test
    fun roundTripsNonAscii() {
        val secret = "clé-密钥-🔑"
        assertEquals(secret, SecretCipher.decrypt(key, SecretCipher.encrypt(key, secret)))
    }

    @Test
    fun ciphertextDoesNotContainPlaintext() {
        val secret = "sk-ant-api03-plaintext-check"
        val encoded = SecretCipher.encrypt(key, secret)
        assertFalse(encoded.contains(secret))
        assertFalse(String(Base64.getDecoder().decode(encoded), Charsets.ISO_8859_1).contains(secret))
    }

    @Test
    fun usesFreshIvEachTime() {
        assertNotEquals(SecretCipher.encrypt(key, "same"), SecretCipher.encrypt(key, "same"))
    }

    @Test
    fun wrongKeyReturnsNull() {
        assertNull(SecretCipher.decrypt(newKey(), SecretCipher.encrypt(key, "secret")))
    }

    @Test
    fun tamperedCiphertextReturnsNull() {
        val bytes = Base64.getDecoder().decode(SecretCipher.encrypt(key, "secret"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(SecretCipher.decrypt(key, Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun malformedInputReturnsNull() {
        assertNull(SecretCipher.decrypt(key, "not base64 !!"))
        assertNull(SecretCipher.decrypt(key, Base64.getEncoder().encodeToString(ByteArray(8))))
        assertNull(SecretCipher.decrypt(key, ""))
    }
}

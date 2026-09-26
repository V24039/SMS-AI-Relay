package com.smsairelay.app

import android.app.Application
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ReceiversTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun nextStartedService(): Intent? = shadowOf(app).nextStartedService

    @Before
    fun setUp() {
        SettingsStore.setAllowlist(app, "+91 98765 43210")
    }

    // --- SmsReceiver ---

    private fun smsIntent(from: String, vararg parts: String) =
        Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            .putExtra("pdus", parts.map { SmsPdu.deliver(from, it) }.toTypedArray<Any>())
            .putExtra("format", "3gpp")

    @Test
    fun allowlistedSmsIsHandedToRelayService() {
        SmsReceiver().onReceive(app, smsIntent("+919876543210", "weather?"))

        val started = nextStartedService()
        assertNotNull(started)
        assertEquals(RelayService::class.java.name, started!!.component!!.className)
        assertEquals("+919876543210", started.getStringExtra("sender"))
        assertEquals("weather?", started.getStringExtra("body"))
    }

    @Test
    fun multipartSmsIsJoinedIntoOneMessage() {
        SmsReceiver().onReceive(app, smsIntent("+919876543210", "first half ", "second half"))
        assertEquals("first half second half", nextStartedService()!!.getStringExtra("body"))
    }

    @Test
    fun smsFromUnknownSenderIsDropped() {
        SmsReceiver().onReceive(app, smsIntent("+15550100999", "hi"))
        assertNull(nextStartedService())
    }

    @Test
    fun smsIsDroppedWhenAllowlistIsEmpty() {
        SettingsStore.setAllowlist(app, "")
        SmsReceiver().onReceive(app, smsIntent("+919876543210", "hi"))
        assertNull(nextStartedService())
    }

    @Test
    fun otherActionsAreIgnored() {
        SmsReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(nextStartedService())
    }

    @Test
    fun smsIntentWithoutPdusIsIgnored() {
        SmsReceiver().onReceive(app, Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
        assertNull(nextStartedService())
    }

    // --- BootReceiver ---

    @Test
    fun bootCompletedStartsRelayService() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertStartedWithoutMessage(nextStartedService())
    }

    @Test
    fun appUpdateStartsRelayService() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertStartedWithoutMessage(nextStartedService())
    }

    @Test
    fun bootReceiverIgnoresOtherActions() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_SCREEN_ON))
        assertNull(nextStartedService())
    }

    private fun assertStartedWithoutMessage(started: Intent?) {
        assertNotNull(started)
        assertEquals(RelayService::class.java.name, started!!.component!!.className)
        assertNull(started.getStringExtra("sender"))
    }
}

// Builds a GSM 3GPP SMS-DELIVER PDU, the format the modem hands to SMS_RECEIVED.
// Handles plain ASCII letters, digits, space and basic punctuation (GSM 7-bit
// default alphabet positions match ASCII for those).
object SmsPdu {

    fun deliver(from: String, text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x00) // no SMSC address
        out.write(0x04) // SMS-DELIVER, no more messages to send

        val digits = from.filter { it.isDigit() }
        out.write(digits.length)
        out.write(if (from.startsWith("+")) 0x91 else 0x81)
        out.write(semiOctets(digits))

        out.write(0x00) // protocol identifier
        out.write(0x00) // data coding: GSM 7-bit
        listOf(0x62, 0x90, 0x42, 0x21, 0x43, 0x00, 0x00).forEach(out::write) // timestamp
        out.write(text.length) // user data length in septets
        out.write(pack7Bit(text))
        return out.toByteArray()
    }

    private fun semiOctets(digits: String): ByteArray {
        val padded = if (digits.length % 2 == 1) digits + "F" else digits
        return padded.chunked(2).map { pair ->
            ((pair[1].digitToInt(16) shl 4) or pair[0].digitToInt(16)).toByte()
        }.toByteArray()
    }

    private fun pack7Bit(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text) {
            buffer = buffer or ((ch.code and 0x7F) shl bits)
            bits += 7
            while (bits >= 8) {
                out.write(buffer and 0xFF)
                buffer = buffer ushr 8
                bits -= 8
            }
        }
        if (bits > 0) out.write(buffer and 0xFF)
        return out.toByteArray()
    }
}

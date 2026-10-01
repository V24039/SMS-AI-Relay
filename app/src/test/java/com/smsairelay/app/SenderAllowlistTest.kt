package com.smsairelay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderAllowlistTest {

    private val allowlist = listOf("+91 98765 43210")

    @Test
    fun matchesSameNumberInDifferentFormats() {
        assertTrue(SenderAllowlist.isAllowed("+919876543210", allowlist))
        assertTrue(SenderAllowlist.isAllowed("09876543210", allowlist))
        assertTrue(SenderAllowlist.isAllowed("9876543210", allowlist))
        assertTrue(SenderAllowlist.isAllowed("(987) 654-3210", allowlist))
    }

    @Test
    fun rejectsDifferentNumber() {
        assertFalse(SenderAllowlist.isAllowed("+919876543211", allowlist))
    }

    @Test
    fun rejectsShortcodesAndAlphanumericSenders() {
        assertFalse(SenderAllowlist.isAllowed("56161", listOf("56161")))
        assertFalse(SenderAllowlist.isAllowed("VM-ABCCBK", listOf("VM-ABCCBK")))
    }

    @Test
    fun emptyAllowlistRejectsEveryone() {
        assertFalse(SenderAllowlist.isAllowed("+919876543210", emptyList()))
    }

    @Test
    fun parseSplitsOnNewlinesCommasAndSemicolons() {
        assertEquals(
            listOf("+1 555 0100", "+1 555 0101", "+1 555 0102"),
            SenderAllowlist.parse("+1 555 0100\n +1 555 0101 ,\n\n+1 555 0102;")
        )
    }

    @Test
    fun parseDropsBlankEntries() {
        assertEquals(emptyList<String>(), SenderAllowlist.parse(" \n,;\n "))
    }

    @Test
    fun shortcodeInAllowlistNeverMatchesAnything() {
        assertFalse(SenderAllowlist.isAllowed("+919876543210", listOf("56161")))
    }

    // Seven digits is the shortest number treated as a real phone number.
    @Test
    fun sevenDigitNumbersMatchButSixDoNot() {
        assertTrue(SenderAllowlist.isAllowed("555-0100", listOf("5550100")))
        assertFalse(SenderAllowlist.isAllowed("550100", listOf("550100")))
    }

    @Test
    fun conversationKeyUsesTrailingTenDigits() {
        assertEquals("9876543210", SenderAllowlist.conversationKey("+91 98765 43210"))
    }

    // Senders that aren't phone numbers still get a stable, distinct key.
    @Test
    fun conversationKeyFallsBackToRawSenderForNonNumbers() {
        assertEquals("VM-ABCCBK", SenderAllowlist.conversationKey("VM-ABCCBK"))
    }
}

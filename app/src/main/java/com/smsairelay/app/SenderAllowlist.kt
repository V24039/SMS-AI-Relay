package com.smsairelay.app

// Pure matching logic, no Android dependencies, so Phase 3 can reuse the same
// normalisation for conversation keys and it stays unit-testable.
object SenderAllowlist {

    // Shortcodes (bank/carrier/OTP senders) are shorter than this, and alphanumeric
    // sender IDs normalise to nothing — neither can ever match, so we never reply to them.
    private const val MIN_DIGITS = 7

    // Compare on the trailing national-number digits so "+91 98765 43210",
    // "098765 43210" and "9876543210" all match each other.
    private const val SIGNIFICANT_DIGITS = 10

    fun parse(raw: String): List<String> =
        raw.split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun isAllowed(sender: String, allowlist: List<String>): Boolean {
        val senderDigits = significantDigits(sender) ?: return false
        return allowlist.any { significantDigits(it) == senderDigits }
    }

    private fun significantDigits(number: String): String? {
        val digits = number.filter { it.isDigit() }
        if (digits.length < MIN_DIGITS) return null
        return digits.takeLast(SIGNIFICANT_DIGITS)
    }
}

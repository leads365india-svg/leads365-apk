package com.leads365.apk.core

object PrivacyRedactor {

    private val PHONE_NUMBER_REGEX = Regex("(\\+?\\d{1,4}[\\s-]?)?(\\d{2,4})[\\s-]?(\\d{3,4})[\\s-]?(\\d{2,4})")
    private val AUTH_HEADER_REGEX = Regex("(?i)(Authorization|Bearer|Token|Key)[:\\s=]+['\"]?([a-zA-Z0-9._\\-~]+)['\"]?")

    fun redactPhoneNumber(phone: String?): String {
        if (phone.isNullOrBlank()) return "[EMPTY]"
        val trimmed = phone.trim()
        if (trimmed.length <= 4) return "****"

        // Keep country prefix if any, keep first 2-3 digits, mask middle, keep last 2 digits
        val digits = trimmed.filter { it.isDigit() || it == '+' }
        return if (digits.length > 6) {
            val prefix = digits.take(4)
            val suffix = digits.takeLast(2)
            "$prefix****$suffix"
        } else {
            "${digits.take(2)}****"
        }
    }

    fun redactFilename(filename: String?): String {
        if (filename.isNullOrBlank()) return "[EMPTY_FILENAME]"
        // Replace sequences of 7+ digits (with optional leading +) that look like phone numbers
        return filename.replace(Regex("""\+?\d{7,15}""")) { match ->
            val v = match.value
            "${v.take(2)}***${v.takeLast(2)}"
        }
    }

    fun redactAuthHeaders(headerValue: String?): String {
        if (headerValue.isNullOrBlank()) return ""
        return AUTH_HEADER_REGEX.replace(headerValue) { match ->
            val headerName = match.groupValues[1]
            "$headerName: [REDACTED]"
        }
    }

    fun redactUrl(url: String?): String {
        if (url.isNullOrBlank()) return "[UNCONFIGURED]"
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: "unknown-host"
            val path = uri.path ?: ""
            val scheme = uri.scheme ?: "https"
            "$scheme://$host$path (query redacted)"
        } catch (e: Exception) {
            "[CONFIGURED_TEST_ENDPOINT]"
        }
    }
}

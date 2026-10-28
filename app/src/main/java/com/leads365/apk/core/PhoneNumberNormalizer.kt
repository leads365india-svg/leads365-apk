package com.leads365.apk.core

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber

class PhoneNumberNormalizer(
    private val defaultRegion: String = DEFAULT_REGION
) {
    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    fun normalizeToE164(rawNumber: String?): String? {
        if (rawNumber.isNullOrBlank()) return null
        val cleaned = rawNumber.trim()

        return try {
            val parsedNumber: Phonenumber.PhoneNumber = phoneUtil.parse(cleaned, defaultRegion)
            if (phoneUtil.isValidNumber(parsedNumber)) {
                phoneUtil.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else {
                // If not strictly valid according to libphonenumber, fallback to best-effort cleaning
                fallbackClean(cleaned)
            }
        } catch (e: NumberParseException) {
            fallbackClean(cleaned)
        }
    }

    fun isValid(rawNumber: String?): Boolean {
        if (rawNumber.isNullOrBlank()) return false
        val cleaned = rawNumber.trim()
        val digits = extractDigitsOnly(cleaned)
        if (digits.length < 10) return false
        return try {
            val parsed = phoneUtil.parse(cleaned, defaultRegion)
            phoneUtil.isPossibleNumber(parsed) || phoneUtil.isValidNumber(parsed)
        } catch (e: NumberParseException) {
            digits.length in 10..13
        }
    }

    fun extractDigitsOnly(rawNumber: String?): String {
        if (rawNumber == null) return ""
        return rawNumber.filter { it.isDigit() }
    }

    fun extractLast10Digits(rawNumber: String?): String {
        val digits = extractDigitsOnly(rawNumber)
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

    private fun fallbackClean(raw: String): String? {
        val digits = extractDigitsOnly(raw)
        return when {
            digits.length == 10 -> "+91$digits"
            digits.length == 11 && digits.startsWith("0") -> "+91${digits.drop(1)}"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            digits.isNotEmpty() -> "+$digits"
            else -> null
        }
    }

    companion object {
        const val DEFAULT_REGION = "IN"
    }
}

package com.leads365.apk

import com.leads365.apk.core.PhoneNumberNormalizer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PhoneNumberNormalizerTest {

    private lateinit var normalizer: PhoneNumberNormalizer

    @Before
    fun setUp() {
        normalizer = PhoneNumberNormalizer(PhoneNumberNormalizer.DEFAULT_REGION)
    }

    @Test
    fun testIndianStandard10DigitNormalization() {
        val input = "9876543210"
        val normalized = normalizer.normalizeToE164(input)
        assertEquals("+919876543210", normalized)
    }

    @Test
    fun testIndianNumberWithLeadingZero() {
        val input = "09876543210"
        val normalized = normalizer.normalizeToE164(input)
        assertEquals("+919876543210", normalized)
    }

    @Test
    fun testIndianNumberAlreadyE164() {
        val input = "+919876543210"
        val normalized = normalizer.normalizeToE164(input)
        assertEquals("+919876543210", normalized)
    }

    @Test
    fun testIndianNumberWithSpacesAndHyphens() {
        val input = "+91 98765-43210"
        val normalized = normalizer.normalizeToE164(input)
        assertEquals("+919876543210", normalized)
    }

    @Test
    fun testInternationalUsNumber() {
        val input = "+14155552671"
        val normalized = normalizer.normalizeToE164(input)
        assertEquals("+14155552671", normalized)
    }

    @Test
    fun testInvalidOrBlankNumberGracefulHandling() {
        assertNull(normalizer.normalizeToE164(null))
        assertNull(normalizer.normalizeToE164(""))
        assertNull(normalizer.normalizeToE164("   "))
    }

    @Test
    fun testExtractLast10Digits() {
        assertEquals("9876543210", normalizer.extractLast10Digits("+919876543210"))
        assertEquals("9876543210", normalizer.extractLast10Digits("09876543210"))
        assertEquals("9876543210", normalizer.extractLast10Digits("9876543210"))
    }
}

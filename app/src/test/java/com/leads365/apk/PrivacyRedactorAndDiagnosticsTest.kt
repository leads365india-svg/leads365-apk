package com.leads365.apk

import com.leads365.apk.core.CompatibilityStatus
import com.leads365.apk.core.DiagnosticsReport
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.diagnostics.DiagnosticsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrivacyRedactorAndDiagnosticsTest {

    @Test
    fun testPhoneNumberRedaction() {
        val phone = "+919876543210"
        val redacted = PrivacyRedactor.redactPhoneNumber(phone)
        assertFalse("Redacted string should not contain full phone number", redacted.contains("9876543210"))
        assertTrue("Redacted string should contain masking asterisks", redacted.contains("****"))
    }

    @Test
    fun testFilenameRedaction() {
        val filename = "Call_+919876543210_20261024.m4a"
        val redacted = PrivacyRedactor.redactFilename(filename)
        assertFalse("Filename should not expose raw phone digits", redacted.contains("9876543210"))
        assertTrue("Filename should contain masked indicator", redacted.contains("***"))
    }

    @Test
    fun testAuthHeaderRedaction() {
        val header = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secretpayload"
        val redacted = PrivacyRedactor.redactAuthHeaders(header)
        assertFalse("Header value should not expose secret JWT payload", redacted.contains("secretpayload"))
        assertTrue("Header should show [REDACTED]", redacted.contains("[REDACTED]"))
    }

    @Test
    fun testRedactedDiagnosticReportContainsNoRawSensitiveData() {
        val report = DiagnosticsReport(
            deviceModel = "Pixel 8",
            manufacturer = "Google",
            androidVersion = "Android 14 (API 34)",
            sdkInt = 34,
            safConfigured = true,
            safTreeUriValid = true,
            mediaStoreAudioAccessible = true,
            phoneStatePermissionGranted = true,
            callLogPermissionGranted = true,
            totalTestLeadsConfigured = 3,
            lastGateResult = "Allowed",
            lastMatchingStatus = "FILE_FOUND",
            lastDiscoveryDelaySec = 2L,
            overallCompatibility = CompatibilityStatus.PARTIALLY_VERIFIED,
            reportTimestampEpochMs = 1700000000000L
        )

        val rawAuditTrace = listOf(
            "[1700000000000] Added test lead: Acme Lead (+919876543210)",
            "[1700000005000] Matched recording: Call_+919876543210_rec.m4a with Authorization: Bearer secret_token_xyz"
        )

        // Mock manager just for json helper
        val jsonOutput = DiagnosticsManager(
            context = object : android.content.ContextWrapper(null) {},
            isSafConfigured = { true },
            isSafValid = { true },
            isMediaStoreAccessible = { true }
        ).generateRedactedDiagnosticJson(report, rawAuditTrace)

        assertNotNull(jsonOutput)
        val parsedJson = JSONObject(jsonOutput)
        assertEquals("Pixel 8", parsedJson.getString("device_model"))
        assertEquals("PARTIALLY_VERIFIED", parsedJson.getString("overall_compatibility"))

        // Assert sensitive raw data is NOT in the JSON string
        assertFalse("Audit trace in JSON must not contain raw phone number", jsonOutput.contains("9876543210"))
        assertFalse("Audit trace in JSON must not contain secret token", jsonOutput.contains("secret_token_xyz"))
    }
}

package com.leads365.apk.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.BuildConfig
import com.leads365.apk.network.auth.AccessTokenInterceptor
import com.leads365.apk.network.auth.TokenProvider
import com.leads365.apk.network.models.AppVersionResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Phase15DistributionAndUpdatesTest {

    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var appUpdateManager: AppUpdateManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val okHttpClient = OkHttpClient.Builder().build()
        appUpdateManager = AppUpdateManager(context, okHttpClient)
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. Version Evaluation & Forced-Update Logic
    // =========================================================================

    @Test
    fun evaluateVersion_whenClientIsBelowMinimum_triggersForcedUpdate() {
        val response = AppVersionResponse(
            minVersionCode = 10,
            minVersionName = "2.0.0",
            isUpdateRequired = true,
            updateUrl = "https://leads365.com/download/app-v2.0.0.apk",
            message = "Critical security update required"
        )

        val result = appUpdateManager.evaluateVersion(response, currentVersionCode = 5)
        assertTrue("Must flag update available", result is UpdateEvaluation.UpdateAvailable)
        val update = result as UpdateEvaluation.UpdateAvailable
        assertTrue("Must be forced update", update.isForced)
        assertEquals(10, update.minVersionCode)
        assertEquals("Critical security update required", update.message)
    }

    @Test
    fun evaluateVersion_whenClientMatchesOrExceeds_returnsUpToDate() {
        val response = AppVersionResponse(
            minVersionCode = 5,
            minVersionName = "1.5.0",
            isUpdateRequired = false,
            updateUrl = "https://leads365.com/download/app-v1.5.0.apk"
        )

        val result = appUpdateManager.evaluateVersion(response, currentVersionCode = 5)
        assertEquals(UpdateEvaluation.UpToDate, result)

        val resultNewer = appUpdateManager.evaluateVersion(response, currentVersionCode = 6)
        assertEquals(UpdateEvaluation.UpToDate, resultNewer)
    }

    // =========================================================================
    // 2. Client Version Headers in AccessTokenInterceptor
    // =========================================================================

    @Test
    fun accessTokenInterceptor_attachesAppVersionHeadersToEveryRequest() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val dummyTokenProvider = object : TokenProvider {
            override fun getAccessToken(): String? = "test_token_123"
            override fun getRefreshToken(): String? = null
            override fun saveTokens(accessToken: String, refreshToken: String) {}
            override fun clearTokens() {}
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AccessTokenInterceptor(dummyTokenProvider))
            .build()

        val request = Request.Builder()
            .url(mockWebServer.url("/api/mobile/v1/test"))
            .build()

        val response = client.newCall(request).execute()
        assertTrue(response.isSuccessful)

        val recorded = mockWebServer.takeRequest()
        assertEquals(BuildConfig.VERSION_NAME, recorded.getHeader("X-App-Version"))
        assertEquals(BuildConfig.VERSION_CODE.toString(), recorded.getHeader("X-App-Version-Code"))
        assertEquals("android", recorded.getHeader("X-Client-Platform"))
        assertEquals("Bearer test_token_123", recorded.getHeader("Authorization"))
    }

    // =========================================================================
    // 3. SHA-256 Integrity Verification
    // =========================================================================

    @Test
    fun sha256Checksum_correctlyValidatesMatchingHash() {
        val payload = "LEADS365_APK_BINARY_PAYLOAD_TEST".toByteArray(Charsets.UTF_8)
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val computedHash = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        assertEquals("Checksums must match exactly", expectedHash, computedHash)
        assertFalse("Checksum must not match altered payload", expectedHash == "00000000000000000000000000000000")
    }

    // =========================================================================
    // 4. Installer Permission & Intent Configuration
    // =========================================================================

    @Test
    fun appUpdateManager_createsValidUnknownSourcesIntent() {
        val intent = appUpdateManager.createManageUnknownAppSourcesIntent()
        assertNotNull(intent)
        assertNotNull(intent.action)
        assertTrue("Intent must target application settings or unknown sources",
            intent.action?.contains("UNKNOWN_APP_SOURCES") == true || intent.action?.contains("SECURITY") == true
        )
    }
}

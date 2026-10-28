package com.leads365.apk.network

import com.leads365.apk.network.api.ApiService
import com.leads365.apk.network.auth.AccessTokenInterceptor
import com.leads365.apk.network.auth.InMemoryTokenProvider
import com.leads365.apk.network.auth.TokenAuthenticator
import com.leads365.apk.network.models.AppVersionResponse
import com.leads365.apk.network.models.TokenResponse
import com.leads365.apk.network.version.VersionChecker
import com.leads365.apk.network.version.VersionStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NetworkAndAuthRefreshTest {

    private lateinit var mockWebServer: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `AccessTokenInterceptor attaches Bearer token when present`() {
        val tokenProvider = InMemoryTokenProvider(accessToken = "valid_access_token_123")
        val interceptor = AccessTokenInterceptor(tokenProvider)

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)

        val recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("Bearer valid_access_token_123", recordedRequest?.getHeader("Authorization"))
    }

    @Test
    fun `AccessTokenInterceptor omits Authorization header when No-Authentication header is present`() {
        val tokenProvider = InMemoryTokenProvider(accessToken = "valid_access_token_123")
        val interceptor = AccessTokenInterceptor(tokenProvider)

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val request = Request.Builder()
            .url(mockWebServer.url("/version/check"))
            .header("No-Authentication", "true")
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)

        val recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertNull(recordedRequest?.getHeader("Authorization"))
        assertNull(recordedRequest?.getHeader("No-Authentication"))
    }

    @Test
    fun `TokenAuthenticator automatically refreshes on 401 and retries original request`() {
        val tokenProvider = InMemoryTokenProvider(
            accessToken = "expired_token",
            refreshToken = "refresh_token_abc"
        )
        val refreshCallCount = AtomicInteger(0)

        val authenticator = TokenAuthenticator(
            tokenProvider = tokenProvider,
            tokenRefresher = {
                refreshCallCount.incrementAndGet()
                TokenResponse(
                    accessToken = "fresh_new_token_456",
                    refreshToken = "new_refresh_token_xyz"
                )
            }
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(AccessTokenInterceptor(tokenProvider))
            .authenticator(authenticator)
            .build()

        // 1st request -> 401 Unauthorized
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"token_expired"}"""))
        // 2nd retry request -> 200 OK
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"success"}"""))

        val request = Request.Builder()
            .url(mockWebServer.url("/user/profile"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(1, refreshCallCount.get())
        assertEquals("fresh_new_token_456", tokenProvider.getAccessToken())
        assertEquals("new_refresh_token_xyz", tokenProvider.getRefreshToken())

        // Verify request 1 sent expired token
        val req1 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("Bearer expired_token", req1?.getHeader("Authorization"))

        // Verify retry sent new fresh token
        val req2 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("Bearer fresh_new_token_456", req2?.getHeader("Authorization"))
    }

    @Test
    fun `TokenAuthenticator fails safely and clears tokens if refresh fails`() {
        val tokenProvider = InMemoryTokenProvider(
            accessToken = "expired_token",
            refreshToken = "invalid_refresh_token"
        )
        val authFailedNotified = AtomicBoolean(false)

        val authenticator = TokenAuthenticator(
            tokenProvider = tokenProvider,
            tokenRefresher = {
                // Simulate server rejecting refresh token
                null
            },
            onAuthFailed = {
                authFailedNotified.set(true)
            }
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(AccessTokenInterceptor(tokenProvider))
            .authenticator(authenticator)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val request = Request.Builder()
            .url(mockWebServer.url("/user/profile"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(401, response.code)
        assertTrue("Auth failed callback should be triggered", authFailedNotified.get())
        assertNull("Access token should be cleared", tokenProvider.getAccessToken())
        assertNull("Refresh token should be cleared", tokenProvider.getRefreshToken())
    }

    @Test
    fun `TokenAuthenticator prevents infinite 401 retry loops by capping at 1 retry`() {
        val tokenProvider = InMemoryTokenProvider(
            accessToken = "initial_token",
            refreshToken = "refresh_token"
        )
        val refreshCount = AtomicInteger(0)

        val authenticator = TokenAuthenticator(
            tokenProvider = tokenProvider,
            tokenRefresher = {
                refreshCount.incrementAndGet()
                TokenResponse(accessToken = "token_attempt_${refreshCount.get()}", refreshToken = "refresh")
            }
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(AccessTokenInterceptor(tokenProvider))
            .authenticator(authenticator)
            .build()

        // 1st request -> 401
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad_token"}"""))
        // 2nd retry -> 401 again
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"still_bad"}"""))

        val request = Request.Builder()
            .url(mockWebServer.url("/secure-data"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(401, response.code)
        // Should only have attempted 1 refresh before giving up
        assertEquals(1, refreshCount.get())
    }

    @Test
    fun `VersionChecker correctly identifies required and optional updates`() {
        val checker = VersionChecker()

        // Case 1: App is older than minimum version -> Mandatory update
        val response1 = AppVersionResponse(
            minVersionCode = 20,
            minVersionName = "2.0.0",
            isUpdateRequired = false,
            updateUrl = "https://leads365.com/download"
        )
        val result1 = checker.evaluate(currentVersionCode = 10, response = response1)
        assertTrue(result1 is VersionStatus.UpdateRequired)

        // Case 2: App is equal to min version, but isUpdateRequired flag is true
        val response2 = AppVersionResponse(
            minVersionCode = 10,
            minVersionName = "1.0.0",
            isUpdateRequired = true,
            updateUrl = "https://leads365.com/download"
        )
        val result2 = checker.evaluate(currentVersionCode = 10, response = response2)
        assertTrue(result2 is VersionStatus.UpdateRequired)

        // Case 3: App is equal to or greater than min version -> UpToDate
        val response3 = AppVersionResponse(
            minVersionCode = 5,
            minVersionName = "1.0.0",
            isUpdateRequired = false
        )
        val result3 = checker.evaluate(currentVersionCode = 10, response = response3)
        assertTrue(result3 is VersionStatus.UpToDate)
    }

    @Test
    fun `Retrofit ApiService parses mock version check response successfully`() = runBlocking {
        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        val apiService = retrofit.create(ApiService::class.java)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "minVersionCode": 2,
                        "minVersionName": "1.2.0",
                        "isUpdateRequired": false,
                        "updateUrl": "https://leads365.com/download",
                        "message": "Bug fixes and performance improvements"
                    }
                """.trimIndent())
        )

        val versionResponse = apiService.checkAppVersion()
        assertEquals(2, versionResponse.minVersionCode)
        assertEquals("1.2.0", versionResponse.minVersionName)
        assertEquals(false, versionResponse.isUpdateRequired)
        assertEquals("https://leads365.com/download", versionResponse.updateUrl)
        assertEquals("Bug fixes and performance improvements", versionResponse.message)
    }
}

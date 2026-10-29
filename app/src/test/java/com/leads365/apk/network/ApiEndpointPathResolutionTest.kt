package com.leads365.apk.network

import com.leads365.apk.network.api.AuthApiService
import com.leads365.apk.network.api.CallApiService
import com.leads365.apk.network.api.LeadApiService
import com.leads365.apk.network.api.PolicyApiService
import com.leads365.apk.network.models.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

import com.leads365.apk.network.api.FollowUpApiService
import com.leads365.apk.telephony.CloudTelephonyApiService
import com.leads365.apk.telephony.InitiateCloudCallRequest

/**
 * Validates that Retrofit relative paths resolve to the exact expected
 * backend paths when Base URL ends with `/api/` (matching BuildConfig.BASE_URL).
 *
 * Prevents regressions where relative paths mistakenly include a duplicate "api/" prefix.
 */
class ApiEndpointPathResolutionTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var retrofit: Retrofit
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Real BuildConfig.BASE_URL is "http://${devHost}:5000/api/" or "https://api.leads365.com/api/"
        // MockWebServer url("/api/") creates an HttpUrl ending in "/api/"
        val baseUrl = mockWebServer.url("/api/")
        val contentType = "application/json".toMediaType()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `lead sync resolves to expected path without duplicate api prefix`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"leads":[]}"""))

        val leadApi = retrofit.create(LeadApiService::class.java)
        leadApi.syncLeads()

        val recorded = mockWebServer.takeRequest()
        assertEquals("/api/mobile/v1/leads/sync", recorded.requestUrl?.encodedPath)
        assertFalse(recorded.requestUrl?.encodedPath?.contains("/api/api") == true)
    }

    @Test
    fun `call report resolves to expected path without duplicate api prefix`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"callId":"c1"}"""))

        val callApi = retrofit.create(CallApiService::class.java)
        callApi.reportCall(
            CallReportRequest(
                clientCallId = "call-1",
                leadId = "lead-1",
                phoneNumber = "+919876543210",
                callDirection = "OUTGOING",
                startTimeMs = 1000L,
                durationSeconds = 1L
            )
        )

        val recorded = mockWebServer.takeRequest()
        assertEquals("/api/mobile/v1/calls", recorded.requestUrl?.encodedPath)
        assertFalse(recorded.requestUrl?.encodedPath?.contains("/api/api") == true)
    }

    @Test
    fun `policy endpoints resolve to expected paths without duplicate api prefix`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"policy":{"syncEnabled":true}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"message":"Paused"}"""))

        val policyApi = retrofit.create(PolicyApiService::class.java)

        policyApi.getPolicy()
        val getRequest = mockWebServer.takeRequest()
        assertEquals("/api/mobile/v1/policy", getRequest.requestUrl?.encodedPath)
        assertFalse(getRequest.requestUrl?.encodedPath?.contains("/api/api") == true)

        policyApi.setPauseSyncState(PauseSyncRequest(paused = true))
        val postRequest = mockWebServer.takeRequest()
        assertEquals("/api/mobile/v1/policy/pause", postRequest.requestUrl?.encodedPath)
        assertFalse(postRequest.requestUrl?.encodedPath?.contains("/api/api") == true)
    }

    @Test
    fun `auth endpoints resolve to expected paths without duplicate api prefix`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        val authApi = retrofit.create(AuthApiService::class.java)

        authApi.login(LoginRequest("test@test.com", "pass"))
        assertEquals("/api/mobile/v1/auth/login", mockWebServer.takeRequest().requestUrl?.encodedPath)

        authApi.registerDevice(DeviceRegisterRequest("Pixel", "Google", "14", 34, 1, "1.0.0"))
        assertEquals("/api/mobile/v1/devices/register", mockWebServer.takeRequest().requestUrl?.encodedPath)

        authApi.updatePushToken(PushTokenRequest("token-123"))
        assertEquals("/api/mobile/v1/push-token", mockWebServer.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `telephony endpoints resolve to expected paths without duplicate api prefix`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"call_id":"c1","status":"initiated"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"call_id":"c1","status":"initiated"}"""))

        val telephonyApi = retrofit.create(CloudTelephonyApiService::class.java)

        telephonyApi.initiateCall(
            InitiateCloudCallRequest(
                leadId = "lead-1",
                leadPhone = "+919876543210",
                agentPhone = "+919876543211",
                recordCall = true,
                clientCallId = "call-1"
            )
        )
        val postRequest = mockWebServer.takeRequest()
        assertEquals("/api/telephony/call", postRequest.requestUrl?.encodedPath)
        assertFalse(postRequest.requestUrl?.encodedPath?.contains("/api/api") == true)

        telephonyApi.getCallStatus("c1")
        val getRequest = mockWebServer.takeRequest()
        assertEquals("/api/telephony/call/c1/status", getRequest.requestUrl?.encodedPath)
        assertFalse(getRequest.requestUrl?.encodedPath?.contains("/api/api") == true)
    }

    @Test
    fun `followup endpoints resolve to expected paths without duplicate api prefix`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"followUps":[]}"""))

        val followUpApi = retrofit.create(FollowUpApiService::class.java)

        followUpApi.getFollowUps()
        val getRequest = mockWebServer.takeRequest()
        assertEquals("/api/follow-ups", getRequest.requestUrl?.encodedPath)
        assertFalse(getRequest.requestUrl?.encodedPath?.contains("/api/api") == true)
    }
}

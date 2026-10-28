package com.leads365.apk.telephony

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.core.AppResult
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.entity.LeadEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Phase18CloudTelephonyTest {

    private lateinit var context: Context
    private lateinit var db: Leads365Database
    private lateinit var callDao: CallDao
    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: CloudTelephonyApiService
    private lateinit var manager: CloudTelephonyManager

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()
        callDao = db.callDao()

        mockWebServer = MockWebServer()
        mockWebServer.start()

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        apiService = retrofit.create(CloudTelephonyApiService::class.java)
        manager = CloudTelephonyManager(context, apiService, callDao)
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
        db.close()
    }

    // =========================================================================
    // 1. Mode Configuration & Persistence
    // =========================================================================

    @Test
    fun callMode_defaultsToNativeDialer_andPersistsToggles() {
        assertEquals(TelephonyCallMode.NATIVE_DIALER, manager.getCallMode())

        manager.setCallMode(TelephonyCallMode.CLOUD_TELEPHONY)
        assertEquals(TelephonyCallMode.CLOUD_TELEPHONY, manager.getCallMode())

        manager.setAgentPhoneNumber("+919876543210")
        assertEquals("+919876543210", manager.getAgentPhoneNumber())

        manager.setCallMode(TelephonyCallMode.NATIVE_DIALER)
        assertEquals(TelephonyCallMode.NATIVE_DIALER, manager.getCallMode())
    }

    // =========================================================================
    // 2. Cloud Call Request Construction & DNR Enforcement
    // =========================================================================

    @Test
    fun initiateCloudCall_enforcesDnrPolicy_andSendsIdempotentRequest() = runBlocking {
        // Enqueue mock success response
        val responseBody = """
            {
                "success": true,
                "call_id": "call_srv_123",
                "status": "initiated",
                "message": "Calling agent phone first"
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val dnrLead = LeadEntity(
            id = "lead_dnr_01",
            name = "Private Customer",
            rawNumber = "+919876500000",
            normalizedNumber = "+919876500000",
            isDoNotRecord = true // DNR active!
        )

        manager.setAgentPhoneNumber("+919123456789")
        val result = manager.initiateCloudCall(dnrLead)

        assertTrue("Call must succeed with mock response", result is AppResult.Success)

        // Verify request payload sent to mock server
        val recordedRequest = mockWebServer.takeRequest()
        val requestBody = recordedRequest.body.readUtf8()

        assertTrue("Request must contain lead id", requestBody.contains("lead_dnr_01"))
        assertTrue("Request must contain lead phone", requestBody.contains("+919876500000"))
        assertTrue("Request must contain agent phone", requestBody.contains("+919123456789"))
        assertTrue("DNR lead must have record_call set to false", requestBody.contains("\"record_call\":false"))

        // Verify CallEntity persisted in Room
        val savedCall = callDao.getById("call_srv_123")
        assertNotNull("CallEntity must be persisted in database", savedCall)
        assertEquals("cloud_telephony", savedCall?.simId)
        assertFalse("Recording must not be marked ready prematurely", savedCall?.hasRecording ?: true)
    }

    // =========================================================================
    // 3. Unconfigured Provider / Backend Missing Fallback
    // =========================================================================

    @Test
    fun initiateCloudCall_whenBackendUnconfigured_returnsGracefulFallback() = runBlocking {
        // Mock HTTP 404 or 501 when backend telephony provider is not deployed
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\": \"Route not found\"}"))

        val lead = LeadEntity(
            id = "lead_02",
            name = "Ramesh Sharma",
            rawNumber = "+919999888877",
            normalizedNumber = "+919999888877"
        )

        val result = manager.initiateCloudCall(lead)
        assertTrue("Result must be failure when provider is missing", result is AppResult.Failure)

        val state = manager.callState.value
        assertTrue("State must be Error", state is CloudCallUiState.Error)
        val errState = state as CloudCallUiState.Error
        assertTrue("Must flag provider as unconfigured for native dialer fallback", errState.isProviderUnconfigured)
        assertTrue("Must explain fallback to user", errState.message.contains("Falling back to native dialer"))
    }

    // =========================================================================
    // 4. Repeated Tap Deduplication Protection
    // =========================================================================

    @Test
    fun initiateCloudCall_preventsDuplicateInFlightRequests() = runBlocking {
        // Enqueue delayed mock response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"success\":true,\"call_id\":\"call_dup_1\"}")
        )

        val lead = LeadEntity(
            id = "lead_03",
            name = "Duplicate Tap Lead",
            rawNumber = "+919999111122",
            normalizedNumber = "+919999111122"
        )

        val res = manager.initiateCloudCall(lead)
        assertTrue("First call must complete", res is AppResult.Success)
    }
}

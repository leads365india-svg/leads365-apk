package com.leads365.apk.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.data.repository.AuthRepositoryImpl
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.network.api.AuthApiService
import com.leads365.apk.network.auth.EncryptedTokenProvider
import com.leads365.apk.network.models.*
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AuthFlowMockServerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var tokenProvider: EncryptedTokenProvider
    private lateinit var authApiService: AuthApiService
    private lateinit var authRepository: AuthRepository

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        context = ApplicationProvider.getApplicationContext()
        tokenProvider = EncryptedTokenProvider(context)
        tokenProvider.clearAllSession()

        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        authApiService = retrofit.create(AuthApiService::class.java)
        authRepository = AuthRepositoryImpl(
            authApiService = authApiService,
            encryptedTokenProvider = tokenProvider,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun teardown() {
        tokenProvider.clearAllSession()
        mockWebServer.shutdown()
    }

    @Test
    fun `successful login saves tokens, session info, and updates authState to Authenticated`() = runBlocking {
        val mockLoginJson = """
            {
                "success": true,
                "data": {
                    "accessToken": "jwt_access_12345",
                    "refreshToken": "jwt_refresh_67890",
                    "expiresInSec": 86400,
                    "user": {
                        "id": "usr_agent_01",
                        "name": "Jane Doe",
                        "email": "jane@leads365.com",
                        "role": "agent"
                    },
                    "agency": {
                        "id": "agn_001",
                        "name": "Prestige Realty Group",
                        "accountId": "ACC-5521"
                    },
                    "minVersionCode": 1,
                    "isUpdateRequired": false,
                    "isRevoked": false
                },
                "message": "Login successful"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockLoginJson))

        val result = authRepository.login("jane@leads365.com", "securePassword123")
        assertTrue("Login must succeed", result is AppResult.Success)

        val authData = (result as AppResult.Success).data
        assertEquals("jwt_access_12345", authData.accessToken)
        assertEquals("Jane Doe", authData.user.name)
        assertEquals("Prestige Realty Group", authData.agency?.name)

        // Verify tokens are stored in EncryptedTokenProvider
        assertEquals("jwt_access_12345", tokenProvider.getAccessToken())
        assertEquals("jwt_refresh_67890", tokenProvider.getRefreshToken())
        assertEquals("Prestige Realty Group", authRepository.getAgencyName())
        assertEquals(true, authRepository.isLoggedIn())

        val state = authRepository.authState.value
        assertTrue("Auth state must be Authenticated", state is AuthState.Authenticated)
        assertEquals("Jane Doe", (state as AuthState.Authenticated).user.name)

        // Verify HTTP request made
        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("/mobile/v1/auth/login", recorded?.path)
    }

    @Test
    fun `invalid credentials returns 401 error and does not store tokens`() = runBlocking {
        val mockErrorJson = """
            {
                "success": false,
                "error": "Invalid email or password",
                "errorCode": "INVALID_CREDENTIALS"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody(mockErrorJson))

        val result = authRepository.login("wrong@leads365.com", "badPassword")
        assertTrue("Login must fail", result is AppResult.Failure)

        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.NetworkError)
        assertEquals(401, (error as AppError.NetworkError).httpCode)
        assertEquals("Invalid email or password", error.message)

        assertFalse("Tokens must not be saved on failure", authRepository.isLoggedIn())
        assertNull(tokenProvider.getAccessToken())
    }

    @Test
    fun `suspended account returns 403 error with clear message`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"success":false,"error":"Account has been suspended"}""")
        )

        val result = authRepository.login("suspended@agency.com", "password")
        assertTrue(result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertTrue(error.message.contains("suspended"))
    }

    @Test
    fun `blank email or password fails fast with validation error`() = runBlocking {
        val result = authRepository.login("", "")
        assertTrue(result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.ValidationError)
        assertEquals(0, mockWebServer.requestCount) // Zero network requests sent
    }

    @Test
    fun `registerDevice stores deviceId and updates auth state`() = runBlocking {
        val mockRegisterJson = """
            {
                "success": true,
                "data": {
                    "deviceId": "dev_vivo_v2111_9988",
                    "status": "ACTIVE",
                    "registeredAt": 1729780000000
                },
                "message": "Device registered successfully"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockRegisterJson))

        val result = authRepository.registerDevice(
            deviceModel = "V2111",
            manufacturer = "vivo",
            osVersion = "12",
            sdkInt = 31,
            appVersionCode = 1,
            appVersionName = "1.0.0"
        )

        assertTrue(result is AppResult.Success)
        val deviceData = (result as AppResult.Success).data
        assertEquals("dev_vivo_v2111_9988", deviceData.deviceId)
        assertEquals("dev_vivo_v2111_9988", authRepository.getDeviceId())
    }

    @Test
    fun `server response declaring revoked account clears tokens and updates state to SessionRevoked`() = runBlocking {
        val mockRevokedJson = """
            {
                "success": true,
                "data": {
                    "accessToken": "revoked_token",
                    "refreshToken": null,
                    "user": { "id": "1", "name": "Agent", "email": "a@b.com" },
                    "isRevoked": true
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockRevokedJson))

        val result = authRepository.login("revoked@agency.com", "password")
        assertTrue(result is AppResult.Failure)
        assertFalse(authRepository.isLoggedIn())
        assertTrue(authRepository.authState.value is AuthState.SessionRevoked)
    }

    @Test
    fun `server response declaring update required blocks login and sets UpdateRequired state`() = runBlocking {
        val mockUpdateJson = """
            {
                "success": true,
                "data": {
                    "accessToken": "token",
                    "refreshToken": null,
                    "user": { "id": "1", "name": "Agent", "email": "a@b.com" },
                    "minVersionCode": 9999,
                    "isUpdateRequired": true
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(mockUpdateJson))

        val result = authRepository.login("agent@agency.com", "password")
        assertTrue(result is AppResult.Failure)
        assertTrue(authRepository.authState.value is AuthState.UpdateRequired)
    }

    @Test
    fun `logout clears tokens and restores state to Unauthenticated`() = runBlocking {
        tokenProvider.saveTokens("temp_token", "temp_refresh")
        assertTrue(authRepository.isLoggedIn())

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        val logoutResult = authRepository.logout(preservePendingUploads = true)
        assertTrue(logoutResult is AppResult.Success)
        assertFalse(authRepository.isLoggedIn())
        assertNull(tokenProvider.getAccessToken())
        assertTrue(authRepository.authState.value is AuthState.Unauthenticated)
    }
}

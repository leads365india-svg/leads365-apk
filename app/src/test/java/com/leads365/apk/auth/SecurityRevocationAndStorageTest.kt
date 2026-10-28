package com.leads365.apk.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.data.repository.AuthRepositoryImpl
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.database.Leads365Database
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.api.AuthApiService
import com.leads365.apk.network.auth.EncryptedTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SecurityRevocationAndStorageTest {

    private lateinit var context: Context
    private lateinit var database: Leads365Database
    private lateinit var tokenProvider: EncryptedTokenProvider
    private lateinit var authRepository: AuthRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, Leads365Database::class.java)
            .allowMainThreadQueries()
            .build()

        tokenProvider = EncryptedTokenProvider(context)
        tokenProvider.clearAllSession()

        val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        val retrofit = Retrofit.Builder()
            .baseUrl("http://127.0.0.1:8080/")
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val authApiService = retrofit.create(AuthApiService::class.java)

        authRepository = AuthRepositoryImpl(
            authApiService = authApiService,
            encryptedTokenProvider = tokenProvider,
            leadDao = database.leadDao(),
            callDao = database.callDao(),
            uploadTaskDao = database.uploadTaskDao(),
            ioDispatcher = Dispatchers.Unconfined,
            context = context
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun confirmedRevocation_remoteWipe_clearsTokensAndCachesAndSetsSessionRevoked() = runBlocking {
        // 1. Seed sensitive credentials, tokens and database records
        tokenProvider.saveTokens("active_access_token_sec", "active_refresh_token_sec")
        tokenProvider.saveSessionInfo(
            userId = "usr_agent_007",
            userName = "Secret Agent",
            userEmail = "agent@leads365.com",
            agencyName = "Secure Agency",
            agencyAccountId = "agn_secure"
        )
        tokenProvider.saveDeviceId("dev_hw_12345")

        database.leadDao().insert(
            LeadEntity(
                id = "lead_secret_01",
                normalizedNumber = "+919876543210",
                rawNumber = "09876543210",
                name = "Confidential Lead",
                status = "Active",
                assignedAgentId = "usr_agent_007"
            )
        )

        database.callDao().insert(
            CallEntity(
                id = "call_secret_01",
                normalizedNumber = "+919876543210",
                rawNumber = "09876543210",
                callType = 2,
                startTimeEpochMs = System.currentTimeMillis() - 5000,
                durationSeconds = 60,
                isLeadCall = true,
                leadId = "lead_secret_01"
            )
        )

        database.uploadTaskDao().insert(
            UploadTaskEntity(
                id = "task_secret_01",
                callId = "call_secret_01",
                localFileUri = "content://media/external/audio/media/999",
                fileName = "call_9876543210.m4a",
                fileSizeBytes = 1024L,
                sha256Hash = "dummy_sha256",
                status = UploadTaskEntity.STATUS_PENDING
            )
        )

        // Verify state prior to revocation
        assertTrue("User should be logged in before revocation", authRepository.isLoggedIn())
        assertEquals(1, database.leadDao().getAllLeads().size)
        assertEquals(1, database.callDao().count())
        assertEquals(1, database.uploadTaskDao().getPendingTasks().size)

        // 2. Trigger Confirmed Revocation Remote Wipe
        val revocationReason = "Device revoked by server administrator"
        authRepository.invalidateSession(revocationReason)

        // 3. Verify SessionRevoked state and login flag
        val currentAuthState = authRepository.authState.value
        assertTrue("State must be SessionRevoked", currentAuthState is AuthState.SessionRevoked)
        assertEquals(revocationReason, (currentAuthState as AuthState.SessionRevoked).reason)
        assertFalse("isLoggedIn must be false post-revocation", authRepository.isLoggedIn())

        // 4. Verify Keystore/Encrypted tokens are completely purged
        assertNull("Access token must be purged", tokenProvider.getAccessToken())
        assertNull("Refresh token must be purged", tokenProvider.getRefreshToken())
        assertNull("User ID must be purged", tokenProvider.getUserId())
        assertNull("Device ID must be purged", tokenProvider.getDeviceId())

        // 5. Verify local Room caches are completely wiped
        assertEquals("Lead cache must be wiped", 0, database.leadDao().getAllLeads().size)
        assertEquals("Call cache must be wiped", 0, database.callDao().count())
        assertEquals("Pending upload tasks must be wiped to prevent post-revocation upload", 0, database.uploadTaskDao().getPendingTasks().size)
    }

    @Test
    fun confirmedRevocation_isIdempotent() {
        tokenProvider.saveTokens("tok1", "tok2")
        authRepository.invalidateSession("Revocation 1")
        assertEquals(AuthState.SessionRevoked("Revocation 1"), authRepository.authState.value)

        // Second call should execute safely without error
        authRepository.invalidateSession("Revocation 2")
        assertEquals(AuthState.SessionRevoked("Revocation 2"), authRepository.authState.value)
        assertFalse(authRepository.isLoggedIn())
    }

    @Test
    fun privacyRedactor_masksSensitivePhoneNumbersAndAuthHeaders() {
        val phone = "+919876543210"
        val redactedPhone = PrivacyRedactor.redactPhoneNumber(phone)
        assertFalse("Redacted phone must not expose middle digits", redactedPhone.contains("987654"))
        assertTrue("Redacted phone must have masking", redactedPhone.contains("****"))

        val authHeader = "Authorization: Bearer secret_jwt_token_12345"
        val redactedHeader = PrivacyRedactor.redactAuthHeaders(authHeader)
        assertFalse("Redacted header must not expose token string", redactedHeader.contains("secret_jwt_token_12345"))
        assertTrue("Redacted header must indicate redaction", redactedHeader.contains("[REDACTED]"))

        val filename = "recording_+919876543210_20261028.m4a"
        val redactedFilename = PrivacyRedactor.redactFilename(filename)
        assertFalse("Filename must not expose phone number", redactedFilename.contains("9876543210"))
        assertTrue("Filename must have masking", redactedFilename.contains("***"))
    }
}

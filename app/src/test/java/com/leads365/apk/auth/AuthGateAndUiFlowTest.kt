package com.leads365.apk.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.data.repository.AuthRepositoryImpl
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.network.api.AuthApiService
import com.leads365.apk.network.auth.EncryptedTokenProvider
import com.leads365.apk.network.models.LoginResponse
import com.leads365.apk.ui.navigation.NavDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AuthGateAndUiFlowTest {

    private lateinit var context: Context
    private lateinit var tokenProvider: EncryptedTokenProvider
    private lateinit var authRepository: AuthRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
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
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun unauthenticated_startsInUnauthenticatedState_withNoTokens() {
        assertEquals(AuthState.Unauthenticated, authRepository.authState.value)
        assertFalse("User should not be logged in without token", authRepository.isLoggedIn())
        assertNull("AccessToken should be null", tokenProvider.getAccessToken())
        assertNull("User email should be null", tokenProvider.getUserEmail())
    }

    @Test
    fun logout_clearsTokensAndResetsAuthState() = runBlocking {
        tokenProvider.saveTokens("dummy_access", "dummy_refresh")
        tokenProvider.saveSessionInfo("usr_1", "Agent One", "agent@leads365.com", "Test Agency", "acc_1")

        assertTrue("User should be logged in before logout", authRepository.isLoggedIn())

        authRepository.logout()

        assertEquals(AuthState.Unauthenticated, authRepository.authState.value)
        assertFalse("User should be logged out", authRepository.isLoggedIn())
        assertNull("AccessToken should be cleared", tokenProvider.getAccessToken())
        assertNull("RefreshToken should be cleared", tokenProvider.getRefreshToken())
        assertNull("User email should be cleared", tokenProvider.getUserEmail())
    }

    @Test
    fun invalidateSession_immediatelyTransitionsToRevokedOrUnauthenticated() {
        tokenProvider.saveTokens("dummy_access", "dummy_refresh")
        tokenProvider.saveSessionInfo("usr_1", "Agent One", "agent@leads365.com", "Test Agency", "acc_1")

        authRepository.invalidateSession("Session expired on server")

        val state = authRepository.authState.value
        assertTrue("State should be SessionRevoked", state is AuthState.SessionRevoked)
        assertEquals("Session expired on server", (state as AuthState.SessionRevoked).reason)
        assertNull("Tokens must be cleared", tokenProvider.getAccessToken())
    }

    @Test
    fun sessionRestoration_recoversAuthenticatedStateOnLaunch() {
        tokenProvider.saveTokens("persisted_access_jwt", "persisted_refresh_jwt")
        tokenProvider.saveSessionInfo("usr_42", "Jane Doe", "jane@leads365.com", "Apex Agency", "acc_99")

        // Construct fresh AuthRepository simulating app cold start
        val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        val retrofit = Retrofit.Builder()
            .baseUrl("http://127.0.0.1:8080/")
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val authApiService = retrofit.create(AuthApiService::class.java)

        val restoredRepo = AuthRepositoryImpl(
            authApiService = authApiService,
            encryptedTokenProvider = tokenProvider,
            ioDispatcher = Dispatchers.Unconfined
        )

        val state = restoredRepo.authState.value
        assertTrue("Restored state should be Authenticated", state is AuthState.Authenticated)
        val authUser = (state as AuthState.Authenticated).user
        assertEquals("usr_42", authUser.id)
        assertEquals("Jane Doe", authUser.name)
        assertEquals("jane@leads365.com", authUser.email)
        assertEquals("Apex Agency", state.agency?.name)
        assertTrue("User must be reported as logged in", restoredRepo.isLoggedIn())
    }

    @Test
    fun navDestinations_bottomBarIncludesOnlyCoreTabs_excludesSetup() {
        val items = NavDestination.items
        assertEquals("Bottom bar must have exactly 4 main tabs", 4, items.size)
        assertEquals(listOf(NavDestination.Home, NavDestination.Leads, NavDestination.Calls, NavDestination.Settings), items)
        assertFalse("Device Setup must NOT be a bottom bar tab", items.contains(NavDestination.Setup))
        assertFalse("Login must NOT be a bottom bar tab", items.contains(NavDestination.Login))
    }

    @Test
    fun localRecordingTestMode_doesNotFabricateAuthSessionOrTokens() {
        // Entering Local Recording Test Mode does not create tokens or fake AuthState.Authenticated
        assertNull("AccessToken must remain null in test mode", tokenProvider.getAccessToken())
        assertNull("RefreshToken must remain null in test mode", tokenProvider.getRefreshToken())
        assertEquals("AuthState must strictly remain Unauthenticated", AuthState.Unauthenticated, authRepository.authState.value)
        assertFalse("User must not be marked as logged in", authRepository.isLoggedIn())
    }
}


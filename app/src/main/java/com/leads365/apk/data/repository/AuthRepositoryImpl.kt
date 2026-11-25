package com.leads365.apk.data.repository

import com.leads365.apk.BuildConfig
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.network.api.AuthApiService
import com.leads365.apk.network.auth.EncryptedTokenProvider
import com.leads365.apk.network.models.AgencyDto
import com.leads365.apk.network.models.AuthData
import com.leads365.apk.network.models.DeviceData
import com.leads365.apk.network.models.DeviceRegisterRequest
import com.leads365.apk.network.models.LoginRequest
import com.leads365.apk.network.models.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.content.Context
import android.util.Base64
import com.leads365.apk.database.dao.CallDao
import com.leads365.apk.database.dao.LeadDao
import com.leads365.apk.database.dao.UploadTaskDao
import com.leads365.apk.work.WorkManagerScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl(
    private val authApiService: AuthApiService,
    private val encryptedTokenProvider: EncryptedTokenProvider,
    private val leadDao: LeadDao? = null,
    private val callDao: CallDao? = null,
    private val uploadTaskDao: UploadTaskDao? = null,
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val context: Context? = null,
    private val workManagerScheduler: WorkManagerScheduler? = null
) : AuthRepository {

    @Inject
    constructor(
        authApiService: AuthApiService,
        encryptedTokenProvider: EncryptedTokenProvider,
        leadDao: LeadDao,
        callDao: CallDao,
        uploadTaskDao: UploadTaskDao,
        @ApplicationContext context: Context,
        workManagerScheduler: WorkManagerScheduler
    ) : this(
        authApiService = authApiService,
        encryptedTokenProvider = encryptedTokenProvider,
        leadDao = leadDao,
        callDao = callDao,
        uploadTaskDao = uploadTaskDao,
        ioDispatcher = Dispatchers.IO,
        context = context,
        workManagerScheduler = workManagerScheduler
    )

    constructor(
        authApiService: AuthApiService,
        encryptedTokenProvider: EncryptedTokenProvider,
        leadDao: LeadDao? = null,
        callDao: CallDao? = null,
        uploadTaskDao: UploadTaskDao? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        context: Context? = null
    ) : this(
        authApiService = authApiService,
        encryptedTokenProvider = encryptedTokenProvider,
        leadDao = leadDao,
        callDao = callDao,
        uploadTaskDao = uploadTaskDao,
        ioDispatcher = ioDispatcher,
        context = context,
        workManagerScheduler = null
    )

    private val sessionMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var pendingCleanupJob: Job? = null

    private val _authState = MutableStateFlow<AuthState>(resolveInitialState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private fun resolveInitialState(): AuthState {
        val token = encryptedTokenProvider.getAccessToken()
        if (token.isNullOrBlank()) {
            return AuthState.Unauthenticated
        }
        val userId = encryptedTokenProvider.getUserId()
        val userEmail = encryptedTokenProvider.getUserEmail() ?: ""
        val userName = encryptedTokenProvider.getUserName() ?: "Agent"
        val effectiveId = when {
            !userId.isNullOrBlank() -> userId
            userEmail.isNotBlank() -> userEmail
            else -> "agent_session"
        }
        val savedWorkspaces = encryptedTokenProvider.getWorkspaces() ?: emptyList()
        val savedActiveWs = encryptedTokenProvider.getActiveWorkspaceId()
        return AuthState.Authenticated(
            user = UserDto(
                id = effectiveId,
                name = userName,
                email = userEmail
            ),
            agency = encryptedTokenProvider.getAgencyName()?.let { AgencyDto(id = "agn_local", name = it) },
            deviceId = encryptedTokenProvider.getDeviceId(),
            workspaces = savedWorkspaces,
            activeWorkspaceId = savedActiveWs
        )
    }

    override fun isLoggedIn(): Boolean {
        return !encryptedTokenProvider.getAccessToken().isNullOrBlank()
    }

    override fun getCurrentUser(): UserDto? {
        if (!isLoggedIn()) return null
        val email = encryptedTokenProvider.getUserEmail() ?: ""
        val userId = encryptedTokenProvider.getUserId() ?: email
        val name = encryptedTokenProvider.getUserName() ?: "Agent"
        return UserDto(id = userId, name = name, email = email)
    }

    override fun getAgencyName(): String? = encryptedTokenProvider.getAgencyName()

    override fun getTenantId(): String? =
        encryptedTokenProvider.getAgencyAccountId()?.trim()?.ifEmpty { null }
            ?: extractTenantIdFromJwt(encryptedTokenProvider.getAccessToken())

    override fun getDeviceId(): String? = encryptedTokenProvider.getDeviceId()

    override fun getWorkspaces(): List<com.leads365.apk.network.models.WorkspaceDto> =
        encryptedTokenProvider.getWorkspaces() ?: emptyList()

    override fun getActiveWorkspaceId(): String? =
        encryptedTokenProvider.getActiveWorkspaceId()

    override fun switchWorkspace(workspaceId: String) {
        val current = _authState.value
        if (current is AuthState.Authenticated) {
            val exists = current.workspaces.any { it.id == workspaceId }
            if (exists || current.workspaces.isEmpty()) {
                encryptedTokenProvider.saveActiveWorkspaceId(workspaceId)
                _authState.value = current.copy(activeWorkspaceId = workspaceId)
            }
        }
    }

    override suspend fun login(email: String, password: String): AppResult<AuthData> = withContext(ioDispatcher) {
        if (email.isBlank() || password.isBlank()) {
            return@withContext AppResult.Failure(AppError.ValidationError("Email and password are required", field = "credentials"))
        }

        // Wait for any asynchronous invalidation/logout DB cleanup to finish so it cannot race with the new login
        pendingCleanupJob?.join()

        sessionMutex.withLock {
            pendingCleanupJob?.join()
            _authState.value = AuthState.Authenticating

            try {
                val response = authApiService.login(
                    LoginRequest(
                        email = email.trim(),
                        password = password,
                        deviceId = encryptedTokenProvider.getDeviceId()
                    )
                )

                if (!response.success || response.data == null) {
                    _authState.value = AuthState.Unauthenticated
                    val err = response.error ?: response.message ?: "Authentication failed"
                    return@withLock AppResult.Failure(AppError.AuthError(err))
                }

                val authData = response.data

                // Check version requirement from server response
                val minVersion = authData.minVersionCode ?: 0
                if (authData.isUpdateRequired || (minVersion > 0 && BuildConfig.VERSION_CODE < minVersion)) {
                    _authState.value = AuthState.UpdateRequired(
                        minVersionCode = minVersion,
                        message = "A mandatory app update is required to access your Leads365 account."
                    )
                    return@withLock AppResult.Failure(
                        AppError.DeviceNotSupportedError("App update required. Minimum version code: $minVersion")
                    )
                }

                // Check if account/device is declared revoked
                if (authData.isRevoked) {
                    cancelWorkManagersSafely()
                    isolateAndPrepareQueueForTenant(null)
                    encryptedTokenProvider.clearAllSession()
                    _authState.value = AuthState.SessionRevoked("This device or user session has been revoked by your agency administrator.")
                    return@withLock AppResult.Failure(
                        AppError.AuthError("Session revoked by administrator")
                    )
                }

                // Cancel lingering workers from any prior session BEFORE validating and switching local storage
                cancelWorkManagersSafely()

                val resolvedTenantId = authData.agency?.accountId?.trim()?.ifEmpty { null }
                    ?: extractTenantIdFromJwt(authData.accessToken)

                // Synchronously isolate leads/calls/uploads: wipe previous tenant's cached data,
                // quarantine mismatched offline calls & recordings without deleting audio,
                // and restore only quarantined items belonging to the authenticated tenant.
                clearLegacyLeadSyncWatermark()
                isolateAndPrepareQueueForTenant(resolvedTenantId)

                // Securely persist tokens in Android Keystore EncryptedSharedPreferences
                encryptedTokenProvider.saveTokens(
                    accessToken = authData.accessToken,
                    refreshToken = authData.refreshToken ?: ""
                )

                encryptedTokenProvider.saveSessionInfo(
                    userId = authData.user.id,
                    userName = authData.user.name,
                    userEmail = authData.user.email,
                    agencyName = authData.agency?.name,
                    agencyAccountId = resolvedTenantId
                )

                // Persist workspaces and resolve initial active workspace
                encryptedTokenProvider.saveWorkspaces(authData.workspaces)
                val resolvedActiveWs = authData.activeWorkspaceId
                    ?: authData.workspaces?.firstOrNull { it.isDefault }?.id
                    ?: authData.workspaces?.firstOrNull()?.id
                encryptedTokenProvider.saveActiveWorkspaceId(resolvedActiveWs)

                _authState.value = AuthState.Authenticated(
                    user = authData.user,
                    agency = authData.agency,
                    deviceId = encryptedTokenProvider.getDeviceId(),
                    workspaces = authData.workspaces ?: emptyList(),
                    activeWorkspaceId = resolvedActiveWs
                )

                // Safely resume only pending uploads belonging to this verified tenant
                try {
                    workManagerScheduler?.resumePendingUploads()
                } catch (_: Exception) {}

                // Re-register push token after login / session restoration
                encryptedTokenProvider.getFcmToken()?.let { fcmToken ->
                    try {
                        val devId = encryptedTokenProvider.getDeviceId()
                        authApiService.updatePushToken(
                            com.leads365.apk.network.models.PushTokenRequest(token = fcmToken, deviceId = devId)
                        )
                    } catch (ignored: Exception) {
                        // Non-blocking
                    }
                }

                AppResult.Success(authData)
            } catch (e: HttpException) {
                _authState.value = AuthState.Unauthenticated
                val code = e.code()
                val msg = when (code) {
                    401 -> "Invalid email or password"
                    403 -> "Account is suspended or access is denied. Please contact support."
                    429 -> "Too many login attempts. Please wait 15 minutes and try again."
                    in 500..599 -> "Server error ($code). Please try again shortly."
                    else -> "Authentication failed with status $code"
                }
                AppResult.Failure(AppError.NetworkError(msg, httpCode = code, cause = e))
            } catch (e: IOException) {
                _authState.value = AuthState.Unauthenticated
                val msg = when {
                    e is java.net.ConnectException -> "Cannot connect to server at ${BuildConfig.BASE_URL}. Ensure internet connection is active and backend service is running."
                    e is java.net.SocketTimeoutException -> "Connection timed out connecting to server at ${BuildConfig.BASE_URL}. Check network latency."
                    e is java.net.UnknownHostException -> "Unable to resolve server host (${e.message}). Check internet connection."
                    e.message?.contains("CLEARTEXT", ignoreCase = true) == true -> "Cleartext HTTP traffic blocked by Android security policy. Ensure network config is enabled."
                    else -> "Network connection error: ${e.localizedMessage ?: e.message ?: "Check your connection."}"
                }
                AppResult.Failure(AppError.NetworkError(msg, cause = e))
            } catch (e: Exception) {
                _authState.value = AuthState.Unauthenticated
                AppResult.Failure(AppError.UnknownError("Unexpected error during login: ${e.message}", cause = e))
            }
        }
    }

    override suspend fun registerDevice(
        deviceModel: String,
        manufacturer: String,
        osVersion: String,
        sdkInt: Int,
        appVersionCode: Int,
        appVersionName: String,
        fcmToken: String?
    ): AppResult<DeviceData> = withContext(ioDispatcher) {
        try {
            val tokenToUse = fcmToken ?: encryptedTokenProvider.getFcmToken()
            val response = authApiService.registerDevice(
                DeviceRegisterRequest(
                    deviceModel = deviceModel,
                    manufacturer = manufacturer,
                    osVersion = osVersion,
                    sdkInt = sdkInt,
                    appVersionCode = appVersionCode,
                    appVersionName = appVersionName,
                    fcmToken = tokenToUse
                )
            )

            if (response.success && response.data != null) {
                encryptedTokenProvider.saveDeviceId(response.data.deviceId)
                if (tokenToUse != null) {
                    encryptedTokenProvider.saveFcmToken(tokenToUse)
                }

                // Update current auth state with new device ID if authenticated
                val current = _authState.value
                if (current is AuthState.Authenticated) {
                    _authState.value = current.copy(deviceId = response.data.deviceId)
                }

                AppResult.Success(response.data)
            } else {
                val err = response.error ?: response.message ?: "Device registration failed"
                AppResult.Failure(AppError.NetworkError(err))
            }
        } catch (e: Exception) {
            AppResult.Failure(AppError.NetworkError("Device registration failed: ${e.message}", cause = e))
        }
    }

    override suspend fun updatePushToken(token: String): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            encryptedTokenProvider.saveFcmToken(token)
            if (isLoggedIn()) {
                val deviceId = encryptedTokenProvider.getDeviceId()
                val response = authApiService.updatePushToken(
                    com.leads365.apk.network.models.PushTokenRequest(
                        token = token,
                        deviceId = deviceId
                    )
                )
                if (response.success) {
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(AppError.NetworkError(response.error ?: "Push token update failed"))
                }
            } else {
                // Saved locally until user logs in
                AppResult.Success(Unit)
            }
        } catch (e: Exception) {
            AppResult.Failure(AppError.NetworkError("Failed to update push token: ${e.message}", cause = e))
        }
    }

    override fun getSavedFcmToken(): String? = encryptedTokenProvider.getFcmToken()

    override fun saveFcmToken(token: String) {
        encryptedTokenProvider.saveFcmToken(token)
    }

    override suspend fun logout(preservePendingUploads: Boolean): AppResult<Unit> = withContext(ioDispatcher) {
        pendingCleanupJob?.join()
        sessionMutex.withLock {
            pendingCleanupJob?.join()
            try {
                // 1. Cancel scheduled background uploads/syncs before clearing session
                cancelWorkManagersSafely()

                // 2. Best effort remote logout notification
                try {
                    authApiService.logout()
                } catch (ignored: Exception) {
                    // Ignore network failure on logout
                }

                // 3. Synchronously clear cached CRM leads and sync watermarks
                try {
                    leadDao?.clearAll()
                } catch (e: Exception) {}
                clearLegacyLeadSyncWatermark()

                if (!preservePendingUploads) {
                    try {
                        callDao?.clearAll()
                    } catch (e: Exception) {}
                    try {
                        uploadTaskDao?.clearAll()
                    } catch (e: Exception) {}
                } else {
                    // Preserve recoverable offline calls & audio tasks strictly in quarantine (never expose to next tenant)
                    try {
                        callDao?.deleteSyncedCalls()
                        callDao?.quarantinePendingCallsForLogout("Session logged out — offline call quarantined for original tenant")
                    } catch (e: Exception) {}
                    try {
                        uploadTaskDao?.quarantineAllNonTerminalTasks("Session logged out — pending upload preserved securely for original tenant")
                    } catch (e: Exception) {}
                }

                // 4. Clear credentials, device session, and tokens
                encryptedTokenProvider.clearAllSession()
                _authState.value = AuthState.Unauthenticated

                AppResult.Success(Unit)
            } catch (e: Exception) {
                encryptedTokenProvider.clearAllSession()
                _authState.value = AuthState.Unauthenticated
                AppResult.Failure(AppError.UnknownError("Logout encountered error: ${e.message}", cause = e))
            }
        }
    }

    override fun invalidateSession(reason: String?) {
        // Immediately halt workers and revoke local session tokens synchronously
        cancelWorkManagersSafely()
        encryptedTokenProvider.clearAllSession()
        _authState.value = if (reason != null) AuthState.SessionRevoked(reason) else AuthState.Unauthenticated

        // Launch tracked cleanup guarded by sessionMutex so a subsequent login() awaits completion
        val previous = pendingCleanupJob
        pendingCleanupJob = cleanupScope.launch {
            previous?.join()
            sessionMutex.withLock {
                try {
                    leadDao?.clearAll()
                } catch (ignored: Exception) {}
                clearLegacyLeadSyncWatermark()
                try {
                    callDao?.deleteSyncedCalls()
                    callDao?.quarantinePendingCallsForLogout("Session invalidated — offline call quarantined")
                } catch (ignored: Exception) {}
                try {
                    uploadTaskDao?.quarantineAllNonTerminalTasks("Session invalidated — offline recording quarantined")
                } catch (ignored: Exception) {}
            }
        }
    }

    private suspend fun isolateAndPrepareQueueForTenant(targetTenantId: String?) {
        try {
            if (targetTenantId != null) {
                callDao?.recoverUnambiguousLegacyCallsForTenant(targetTenantId)
                uploadTaskDao?.recoverUnambiguousLegacyTasksForTenant(targetTenantId)
                leadDao?.deleteLeadsNotForTenant(targetTenantId)
                callDao?.deleteSyncedCallsNotForTenant(targetTenantId)
                callDao?.quarantineMismatchedCalls(
                    activeTenantId = targetTenantId,
                    reason = "Quarantined cross-tenant call (active tenant=$targetTenantId)"
                )
                uploadTaskDao?.quarantineMismatchedTasks(
                    activeTenantId = targetTenantId,
                    reason = "Quarantined cross-tenant upload task (active tenant=$targetTenantId)"
                )
                callDao?.restoreQuarantinedCallsForTenant(targetTenantId)
                uploadTaskDao?.restoreQuarantinedTasksForTenant(targetTenantId)
            } else {
                leadDao?.clearAll()
                callDao?.deleteSyncedCalls()
                callDao?.quarantinePendingCallsForLogout("Unauthenticated or tenantless session — call quarantined")
                uploadTaskDao?.quarantineAllNonTerminalTasks("Unauthenticated or tenantless session — upload quarantined")
            }
        } catch (_: Exception) {}
    }

    private fun cancelWorkManagersSafely() {
        try {
            workManagerScheduler?.cancelAllWorkForIdentityChange()
        } catch (_: Exception) {}
        try {
            context?.let { ctx ->
                androidx.work.WorkManager.getInstance(ctx).cancelAllWork()
            }
        } catch (_: Exception) {}
    }

    private fun clearLegacyLeadSyncWatermark() {
        try {
            context?.getSharedPreferences("leads365_lead_sync_prefs", Context.MODE_PRIVATE)
                ?.edit()
                ?.remove("key_last_sync_epoch_ms")
                ?.apply()
        } catch (_: Exception) {}
    }

    private fun extractTenantIdFromJwt(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val decodedBytes = Base64.decode(
                parts[1],
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            val payload = JSONObject(String(decodedBytes, Charsets.UTF_8))
            payload.optString("tenantId", "").trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}

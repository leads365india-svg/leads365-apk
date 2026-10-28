package com.leads365.apk.data.repository

import android.content.Context
import android.util.Log
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.network.api.PolicyApiService
import com.leads365.apk.network.models.PauseSyncRequest
import com.leads365.apk.network.models.PolicyData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PolicyRepositoryImpl @Inject constructor(
    private val policyApiService: PolicyApiService,
    @ApplicationContext private val context: Context
) : PolicyRepository {

    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // Test-friendly constructor
    constructor(
        policyApiService: PolicyApiService,
        context: Context,
        dispatcher: CoroutineDispatcher
    ) : this(policyApiService, context) {
        this.ioDispatcher = dispatcher
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_POLICY, Context.MODE_PRIVATE)
    }

    private val _policyState = MutableStateFlow(loadCachedPolicy())
    override val policyState: StateFlow<CachedPolicy> = _policyState.asStateFlow()

    private val _consentAcknowledged = MutableStateFlow(prefs.getBoolean(KEY_CONSENT_ACK, false))
    override val consentAcknowledged: StateFlow<Boolean> = _consentAcknowledged.asStateFlow()

    override suspend fun syncPolicy(): AppResult<PolicyData> = withContext(ioDispatcher) {
        try {
            val response = policyApiService.getPolicy()
            if (!response.success || response.data == null) {
                return@withContext AppResult.Failure(
                    AppError.NetworkError(response.error ?: "Policy sync failed")
                )
            }

            val data = response.data
            cachePolicyData(data)

            val updated = CachedPolicy(
                agencyId = data.agencyId,
                agencyName = data.agencyName,
                requireConsentReminder = data.requireConsentReminder,
                consentNoticeText = data.consentNoticeText,
                allowRecordingPause = data.allowRecordingPause,
                recordingSyncPaused = data.recordingSyncPaused,
                enforceStrictDnr = data.enforceStrictDnr,
                lastSyncEpochMs = System.currentTimeMillis()
            )
            _policyState.value = updated

            Log.d(TAG, "Policy synced: agency=${data.agencyName}, dnr=${data.enforceStrictDnr}, paused=${data.recordingSyncPaused}")
            AppResult.Success(data)
        } catch (e: HttpException) {
            val code = e.code()
            if (code == 401 || code == 403) {
                AppResult.Failure(AppError.AuthError("Session expired. Re-login required.", isTokenExpired = true, cause = e))
            } else {
                AppResult.Failure(AppError.NetworkError("Server error $code fetching policy", httpCode = code, cause = e))
            }
        } catch (e: IOException) {
            AppResult.Failure(AppError.NetworkError("Network unavailable for policy sync.", cause = e))
        } catch (e: Exception) {
            AppResult.Failure(AppError.UnknownError("Unexpected policy sync error: ${e.message}", e))
        }
    }

    override suspend fun setPauseSyncState(paused: Boolean): AppResult<Boolean> = withContext(ioDispatcher) {
        try {
            val response = policyApiService.setPauseSyncState(PauseSyncRequest(paused = paused))
            if (!response.success) {
                return@withContext AppResult.Failure(
                    AppError.NetworkError(response.error ?: "Failed to update pause state")
                )
            }

            prefs.edit().putBoolean(KEY_SYNC_PAUSED, response.recordingSyncPaused).apply()
            val current = _policyState.value
            _policyState.value = current.copy(recordingSyncPaused = response.recordingSyncPaused)

            Log.d(TAG, "Recording sync pause=${response.recordingSyncPaused}")
            AppResult.Success(response.recordingSyncPaused)
        } catch (e: HttpException) {
            AppResult.Failure(AppError.NetworkError("Server error setting pause state", httpCode = e.code(), cause = e))
        } catch (e: IOException) {
            AppResult.Failure(AppError.NetworkError("Network unavailable.", cause = e))
        } catch (e: Exception) {
            AppResult.Failure(AppError.UnknownError("Unexpected error: ${e.message}", e))
        }
    }

    override fun acknowledgeConsent() {
        prefs.edit().putBoolean(KEY_CONSENT_ACK, true).apply()
        _consentAcknowledged.value = true
        Log.d(TAG, "User acknowledged privacy consent notice")
    }

    override fun isConsentAcknowledged(): Boolean = prefs.getBoolean(KEY_CONSENT_ACK, false)

    override fun isRecordingSyncPaused(): Boolean = _policyState.value.recordingSyncPaused

    override fun isStrictDnrEnforced(): Boolean = _policyState.value.enforceStrictDnr

    private fun cachePolicyData(data: PolicyData) {
        prefs.edit()
            .putString(KEY_AGENCY_ID, data.agencyId)
            .putString(KEY_AGENCY_NAME, data.agencyName)
            .putBoolean(KEY_REQUIRE_CONSENT, data.requireConsentReminder)
            .putString(KEY_CONSENT_TEXT, data.consentNoticeText)
            .putBoolean(KEY_ALLOW_PAUSE, data.allowRecordingPause)
            .putBoolean(KEY_SYNC_PAUSED, data.recordingSyncPaused)
            .putBoolean(KEY_STRICT_DNR, data.enforceStrictDnr)
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply()
    }

    private fun loadCachedPolicy(): CachedPolicy {
        return CachedPolicy(
            agencyId = prefs.getString(KEY_AGENCY_ID, "default_agency") ?: "default_agency",
            agencyName = prefs.getString(KEY_AGENCY_NAME, "Leads365 Agency") ?: "Leads365 Agency",
            requireConsentReminder = prefs.getBoolean(KEY_REQUIRE_CONSENT, true),
            consentNoticeText = prefs.getString(KEY_CONSENT_TEXT, "") ?: "",
            allowRecordingPause = prefs.getBoolean(KEY_ALLOW_PAUSE, true),
            recordingSyncPaused = prefs.getBoolean(KEY_SYNC_PAUSED, false),
            enforceStrictDnr = prefs.getBoolean(KEY_STRICT_DNR, true),
            lastSyncEpochMs = prefs.getLong(KEY_LAST_SYNC, 0L)
        )
    }

    companion object {
        private const val TAG = "PolicyRepo"
        private const val PREFS_POLICY = "leads365_policy_prefs"
        private const val KEY_AGENCY_ID = "key_agency_id"
        private const val KEY_AGENCY_NAME = "key_agency_name"
        private const val KEY_REQUIRE_CONSENT = "key_require_consent"
        private const val KEY_CONSENT_TEXT = "key_consent_text"
        private const val KEY_ALLOW_PAUSE = "key_allow_pause"
        private const val KEY_SYNC_PAUSED = "key_sync_paused"
        private const val KEY_STRICT_DNR = "key_strict_dnr"
        private const val KEY_LAST_SYNC = "key_last_policy_sync"
        private const val KEY_CONSENT_ACK = "key_consent_acknowledged"
    }
}

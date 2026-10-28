package com.leads365.apk.data.repository

import com.leads365.apk.core.AppResult
import com.leads365.apk.network.models.PolicyData
import kotlinx.coroutines.flow.StateFlow

/**
 * Cached agency privacy policy state.
 */
data class CachedPolicy(
    val agencyId: String = "default_agency",
    val agencyName: String = "Leads365 Agency",
    val requireConsentReminder: Boolean = true,
    val consentNoticeText: String = "",
    val allowRecordingPause: Boolean = true,
    val recordingSyncPaused: Boolean = false,
    val enforceStrictDnr: Boolean = true,
    val lastSyncEpochMs: Long = 0L
)

interface PolicyRepository {
    val policyState: StateFlow<CachedPolicy>
    val consentAcknowledged: StateFlow<Boolean>

    /** Fetch the latest policy from the server and cache it locally. */
    suspend fun syncPolicy(): AppResult<PolicyData>

    /** Toggle recording sync pause state on the server and update local cache. */
    suspend fun setPauseSyncState(paused: Boolean): AppResult<Boolean>

    /** Mark first-run privacy consent as acknowledged by the user. */
    fun acknowledgeConsent()

    /** Check whether consent has been acknowledged. */
    fun isConsentAcknowledged(): Boolean

    /** Check whether recording sync is currently paused (local cache). */
    fun isRecordingSyncPaused(): Boolean

    /** Check whether strict DNR enforcement is active. */
    fun isStrictDnrEnforced(): Boolean
}

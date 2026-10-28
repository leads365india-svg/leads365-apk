package com.leads365.apk.ui.leads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leads365.apk.calls.CallProcessingManager
import com.leads365.apk.calls.PendingCallManager
import com.leads365.apk.core.AppError
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.work.WorkManagerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LeadsUiState(
    val searchQuery: String = "",
    val selectedStatus: String = "ALL",
    val isSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val lastSyncTimeEpochMs: Long = 0L,
    val syncErrorMessage: String? = null,
    val selectedLeadForDetail: LeadEntity? = null,
    val activePostCall: Pair<CallEntity, LeadEntity>? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LeadsViewModel @Inject constructor(
    private val leadRepository: LeadRepository,
    private val workManagerScheduler: WorkManagerScheduler,
    private val pendingCallManager: PendingCallManager? = null,
    private val callProcessingManager: CallProcessingManager? = null,
    private val callRepository: CallRepository? = null,
    private val followUpRepository: com.leads365.apk.data.repository.FollowUpRepository? = null,
    private val cloudTelephonyManager: com.leads365.apk.telephony.CloudTelephonyManager? = null
) : ViewModel() {

    // Secondary constructor for tests that supply only repository & scheduler
    constructor(
        leadRepository: LeadRepository,
        workManagerScheduler: WorkManagerScheduler
    ) : this(leadRepository, workManagerScheduler, null, null, null, null, null)

    private val _uiState = MutableStateFlow(
        LeadsUiState(lastSyncTimeEpochMs = leadRepository.getLastSyncTimestamp())
    )
    val uiState: StateFlow<LeadsUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _selectedStatus = MutableStateFlow("ALL")

    val leads: StateFlow<List<LeadEntity>> = combine(_searchQuery, _selectedStatus) { query, status ->
        Pair(query, status)
    }.flatMapLatest { (query, status) ->
        leadRepository.searchLeadsFlow(query, status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        workManagerScheduler.schedulePeriodicLeadSync()
        syncLeads()

        // Listen for completed lead calls to prompt Post-Call Outcome dialog
        callProcessingManager?.let { manager ->
            viewModelScope.launch {
                manager.completedLeadCalls.collect { callPair ->
                    _uiState.update { it.copy(activePostCall = callPair) }
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onStatusSelected(status: String) {
        _selectedStatus.value = status
        _uiState.update { it.copy(selectedStatus = status) }
    }

    fun selectLeadForDetail(lead: LeadEntity?) {
        _uiState.update { it.copy(selectedLeadForDetail = lead) }
    }

    fun clearSyncError() {
        _uiState.update { it.copy(syncErrorMessage = null) }
    }

    val callMode: com.leads365.apk.telephony.TelephonyCallMode
        get() = cloudTelephonyManager?.getCallMode() ?: com.leads365.apk.telephony.TelephonyCallMode.NATIVE_DIALER

    val cloudCallState = cloudTelephonyManager?.callState
        ?: MutableStateFlow(com.leads365.apk.telephony.CloudCallUiState.Idle)

    fun resetCloudCallState() {
        cloudTelephonyManager?.resetCallState()
    }

    /**
     * Phase 6 / Phase 18 Calling Flow:
     * - In NATIVE_DIALER mode: Records intended pending call and opens native dialer (ACTION_DIAL/CALL).
     * - In CLOUD_TELEPHONY mode: Dispatches cloud bridge call request through CloudTelephonyManager.
     *   On server-confirmed initiation, triggers post-call outcome workflow.
     *   On failure, provides clean fallback path to native dialer.
     */
    fun initiateCall(
        lead: LeadEntity,
        context: Context,
        preferDirectCall: Boolean = false,
        forceNativeDialer: Boolean = false
    ) {
        val currentMode = if (forceNativeDialer) {
            com.leads365.apk.telephony.TelephonyCallMode.NATIVE_DIALER
        } else {
            cloudTelephonyManager?.getCallMode() ?: com.leads365.apk.telephony.TelephonyCallMode.NATIVE_DIALER
        }

        if (currentMode == com.leads365.apk.telephony.TelephonyCallMode.CLOUD_TELEPHONY && cloudTelephonyManager != null) {
            viewModelScope.launch {
                when (val result = cloudTelephonyManager.initiateCloudCall(lead)) {
                    is com.leads365.apk.core.AppResult.Success -> {
                        promptPostCallOutcome(result.data, lead)
                    }
                    is com.leads365.apk.core.AppResult.Failure -> {
                        // Error is surfaced in cloudCallState StateFlow
                    }
                }
            }
            return
        }

        pendingCallManager?.setPendingCall(lead)

        val rawNum = lead.rawNumber.trim()
        val hasDirectCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = if (preferDirectCall && hasDirectCallPerm) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$rawNum"))
        } else {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$rawNum"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // If dialer intent fails, clear pending call
            pendingCallManager?.clearPendingCall()
        }
    }

    fun promptPostCallOutcome(call: CallEntity, lead: LeadEntity) {
        _uiState.update { it.copy(activePostCall = Pair(call, lead)) }
    }

    fun dismissPostCallDialog() {
        _uiState.update { it.copy(activePostCall = null) }
    }

    fun saveCallOutcome(
        callId: String,
        outcome: String,
        notes: String?,
        nextFollowUpEpochMs: Long?
    ) {
        viewModelScope.launch {
            val currentPair = _uiState.value.activePostCall
            val lead = currentPair?.second

            // 1. Update CallEntity in Room
            callRepository?.updateOutcome(callId, outcome, notes, nextFollowUpEpochMs)

            // 2. Update LeadEntity status, nextFollowUp, and notes in Room
            if (lead != null) {
                val updatedNotes = if (!notes.isNullOrBlank()) {
                    if (lead.notes.isBlank()) notes else "${lead.notes}\n$notes"
                } else {
                    lead.notes
                }
                val updatedLead = lead.copy(
                    status = outcome,
                    nextFollowUpEpochMs = nextFollowUpEpochMs ?: lead.nextFollowUpEpochMs,
                    notes = updatedNotes,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                leadRepository.saveLead(updatedLead)

                // Phase 17: Schedule follow-up record & reminder
                if (nextFollowUpEpochMs != null) {
                    try {
                        followUpRepository?.createFollowUp(
                            leadId = lead.id,
                            leadName = lead.name,
                            leadPhone = lead.normalizedNumber.ifBlank { lead.rawNumber },
                            title = "Follow-up ($outcome)",
                            scheduledAtEpochMs = nextFollowUpEpochMs,
                            notes = notes ?: ""
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("LeadsViewModel", "Error auto-creating follow-up: ${e.message}")
                    }
                }
            }

            // 3. Attempt network call reporting in background (keeps PENDING if offline)
            callRepository?.reportCallToBackend(callId)

            _uiState.update { it.copy(activePostCall = null) }
        }
    }

    fun syncLeads(forceFullSync: Boolean = false) {
        if (_uiState.value.isSyncing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncErrorMessage = null) }

            when (val result = leadRepository.syncLeads(forceFullSync)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            isOffline = false,
                            lastSyncTimeEpochMs = result.data.lastSyncTimestampEpochMs,
                            syncErrorMessage = null
                        )
                    }
                }
                is AppResult.Failure -> {
                    val message = when (result.error) {
                        is AppError.RateLimitError -> "Rate limited by server. Retrying later."
                        is AppError.NetworkError -> "Offline: Viewing cached leads."
                        is AppError.AuthError -> "Authentication required to sync leads."
                        else -> result.error.message
                    }
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            isOffline = result.error is AppError.NetworkError,
                            syncErrorMessage = message
                        )
                    }
                }
            }
        }
    }
}

package com.leads365.apk.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.leads365.apk.calls.CallGateManager
import com.leads365.apk.calls.CallLogAccessStatus
import com.leads365.apk.calls.CallLogReader
import com.leads365.apk.calls.CallMonitoringState
import com.leads365.apk.calls.CallStateObserver
import com.leads365.apk.calls.CallStateReceiver
import com.leads365.apk.core.CallDirection
import com.leads365.apk.core.CallEvent
import com.leads365.apk.core.CompatibilityStatus
import com.leads365.apk.core.DiagnosticsReport
import com.leads365.apk.core.GateEvaluationResult
import com.leads365.apk.core.MatchStatus
import com.leads365.apk.core.PermissionItem
import com.leads365.apk.core.PermissionStatus
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.core.PrivacyRedactor
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.core.RecordingMatchResult
import com.leads365.apk.core.TestLead
import com.leads365.apk.core.UploadState
import com.leads365.apk.diagnostics.DiagnosticsManager
import com.leads365.apk.recordings.MediaStoreRecordingSource
import com.leads365.apk.recordings.SafFolderInspection
import com.leads365.apk.recordings.SafRecordingSource
import com.leads365.apk.recordings.StreamingTestUploader
import com.leads365.apk.recordings.TargetedRecordingMatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.leads365.apk.calls.CallLogDiagnosticResult

enum class WizardStep(val stepNumber: Int, val title: String) {
    PERMISSIONS(1, "Phone Permissions"),
    RECORDING_FOLDER(2, "Recording Folder"),
    TEST_CALL(3, "Make a Test Call"),
    TEST_RESULT(4, "Test Result");

    val titleEn: String get() = title
}

enum class RecordingDiscoveryState {
    IDLE,
    WAITING_FOR_RECORDING,
    RECORDING_FOUND,
    NO_RECORDING_FOUND,
    FOLDER_ACCESS_REQUIRED,
    RECORDING_UNAVAILABLE_ON_THIS_DEVICE,
    MULTIPLE_POSSIBLE_RECORDINGS,
    RECORDING_LOOKUP_FAILED,
    RECORDING_UNAVAILABLE_OR_INACCESSIBLE
}

data class MainUiState(
    val currentStep: WizardStep = WizardStep.PERMISSIONS,
    val deviceModel: String = "",
    val manufacturer: String = "",
    val androidVersion: String = "",
    val overallStatus: CompatibilityStatus = CompatibilityStatus.NOT_TESTED,
    val permissions: List<PermissionItem> = emptyList(),
    val callMonitoringState: CallMonitoringState = CallMonitoringState.IdleWaiting,
    val callLogAccessStatus: CallLogAccessStatus = CallLogAccessStatus.ACCESSIBLE,
    val callLogDiagnostic: CallLogDiagnosticResult = CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS,
    val callLogErrorMessage: String? = null,
    val safUriString: String? = null,
    val safConfigured: Boolean = false,
    val safValid: Boolean = false,
    val safInspection: SafFolderInspection? = null,
    val mediaStoreAccessible: Boolean = false,
    val activeDiscoveryMethod: RecordingDiscoveryMethod = RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK,
    val testLeads: List<TestLead> = emptyList(),
    val selectedLeadId: String? = null,
    val recentCallEvent: CallEvent? = null,
    val isCallSimulated: Boolean = false,
    val isSearchingRecording: Boolean = false,
    val recordingDiscoveryState: RecordingDiscoveryState = RecordingDiscoveryState.IDLE,
    val folderQuerySuccess: Boolean? = null,
    val folderQueryMessage: String = "",
    val gateResult: GateEvaluationResult? = null,
    val candidateRecordings: List<RecordingCandidate> = emptyList(),
    val matchingResult: RecordingMatchResult? = null,
    val uploadEndpointUrl: String = "",
    val uploadState: UploadState = UploadState.Idle,
    val showUploadConfirmation: Boolean = false,
    val selectedCandidateForUpload: RecordingCandidate? = null,
    val selectedCandidateChecksum: String = "",
    val showAdvancedDiagnostics: Boolean = false,
    val auditLogs: List<String> = emptyList(),
    val dialerRecordingConfirmed: Boolean = false
) {
    val isRecordingVerified: Boolean
        get() = matchingResult?.status == MatchStatus.FILE_FOUND && matchingResult.matchedCandidate != null

    val selectedLead: TestLead?
        get() = testLeads.find { it.id == selectedLeadId } ?: testLeads.firstOrNull()

    /**
     * Gating rules for the guided 4-step wizard
     */
    val canContinueToStep2: Boolean
        get() {
            val mandatory = permissions.filter { it.isMandatory }
            return mandatory.isNotEmpty() && mandatory.all { it.status == PermissionStatus.GRANTED }
        }

    val canContinueToStep3: Boolean
        get() = (safConfigured && safValid) || mediaStoreAccessible

    val canContinueToStep4: Boolean
        get() = recentCallEvent != null && gateResult != null
}

enum class SimulatedCallScenario {
    MATCHED_TEST_LEAD,
    DO_NOT_RECORD_LEAD,
    NON_LEAD_NUMBER,
    AMBIGUOUS_RECORDINGS
}



@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val safRecordingSource: SafRecordingSource,
    private val mediaStoreRecordingSource: MediaStoreRecordingSource,
    private val matcher: TargetedRecordingMatcher
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        safRecordingSource = SafRecordingSource(
            application.applicationContext,
            getTreeUri = {
                val prefs = application.applicationContext.getSharedPreferences("com.leads365.apk.preferences", Context.MODE_PRIVATE)
                prefs.getString("key_saf_tree_uri", null)?.let {
                    try { Uri.parse(it) } catch (e: Exception) { null }
                }
            }
        ),
        mediaStoreRecordingSource = MediaStoreRecordingSource(application.applicationContext),
        matcher = TargetedRecordingMatcher(PhoneNumberNormalizer())
    )

    private val context: Context get() = getApplication<Application>().applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val normalizer = PhoneNumberNormalizer()
    private val callGateManager = CallGateManager(normalizer)
    private val callLogReader = CallLogReader(context, normalizer)
    private val uploader = StreamingTestUploader(context)

    private val diagnosticsManager = DiagnosticsManager(
        context = context,
        isSafConfigured = { safRecordingSource.isConfigured() },
        isSafValid = { safRecordingSource.isAccessible() },
        isMediaStoreAccessible = { mediaStoreRecordingSource.isAccessible() },
        diagnoseCallLog = { callLogReader.diagnoseCallLogAccess() }
    )

    private val callStateObserver = CallStateObserver(
        context = context,
        callLogReader = callLogReader,
        scope = viewModelScope,
        onCallCompleted = { call -> handleCallEnded(call, isSimulated = false) }
    )

    private val _uiState = MutableStateFlow(
        MainUiState(
            deviceModel = diagnosticsManager.getDeviceModel(),
            manufacturer = diagnosticsManager.getManufacturer(),
            androidVersion = diagnosticsManager.getAndroidVersion(),
            testLeads = callGateManager.getTestLeads(),
            uploadEndpointUrl = prefs.getString(KEY_UPLOAD_ENDPOINT, "") ?: ""
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _pendingDestination = MutableStateFlow<String?>(null)
    val pendingDestination: StateFlow<String?> = _pendingDestination.asStateFlow()

    fun setPendingDestination(dest: String?) {
        _pendingDestination.value = dest
    }

    fun clearPendingDestination() {
        _pendingDestination.value = null
    }

    private val wizardCallListener: (CallEvent) -> Unit = { call ->
        handleCallEnded(call, isSimulated = false)
    }

    init {
        // Wire broadcast receiver fallback
        CallStateReceiver.addCallDetectedListener(wizardCallListener)

        // Load persisted test leads or initialize default leads
        val savedLeadsJson = prefs.getString(KEY_SAVED_LEADS, null)
        if (!savedLeadsJson.isNullOrBlank()) {
            val loaded = deserializeLeads(savedLeadsJson)
            callGateManager.setLeads(loaded)
        } else {
            val defaults = CallGateManager.defaultTestLeads()
            callGateManager.setLeads(defaults)
            persistLeads(defaults)
        }
        val savedLeadId = prefs.getString(KEY_SELECTED_LEAD_ID, null)
        val initialSelectedLeadId = savedLeadId?.takeIf { id ->
            callGateManager.getTestLeads().any { it.id == id }
        } ?: callGateManager.getTestLeads().firstOrNull()?.id
        _uiState.update { it.copy(selectedLeadId = initialSelectedLeadId) }

        // Observe call monitoring state from observer
        viewModelScope.launch {
            callStateObserver.monitoringState.collect { mState ->
                _uiState.update { it.copy(callMonitoringState = mState) }
            }
        }

        // Load dialer recording confirmation
        val dialerConfirmed = prefs.getBoolean(KEY_DIALER_RECORDING_CONFIRMED, false)
        _uiState.update { it.copy(dialerRecordingConfirmed = dialerConfirmed) }

        // Load saved state and permissions
        refreshState()

        // Observe upload state
        viewModelScope.launch {
            uploader.uploadState.collect { state ->
                _uiState.update { it.copy(uploadState = state) }
            }
        }

        // Observe SAF folder update events reactively
        viewModelScope.launch {
            safRecordingSource.folderUpdateEvents.collect {
                refreshState()
                verifyFolderAccess()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        callStateObserver.stopObserving()
        CallStateReceiver.removeCallDetectedListener(wizardCallListener)
    }

    fun setWizardStep(step: WizardStep) {
        _uiState.update { it.copy(currentStep = step) }
    }

    fun nextStep() {
        val current = _uiState.value.currentStep
        val next = when (current) {
            WizardStep.PERMISSIONS -> if (_uiState.value.canContinueToStep2) WizardStep.RECORDING_FOLDER else current
            WizardStep.RECORDING_FOLDER -> if (_uiState.value.canContinueToStep3) WizardStep.TEST_CALL else current
            WizardStep.TEST_CALL -> if (_uiState.value.canContinueToStep4) WizardStep.TEST_RESULT else current
            WizardStep.TEST_RESULT -> WizardStep.TEST_RESULT
        }
        _uiState.update { it.copy(currentStep = next) }
    }

    fun previousStep() {
        val current = _uiState.value.currentStep
        val prev = when (current) {
            WizardStep.PERMISSIONS -> WizardStep.PERMISSIONS
            WizardStep.RECORDING_FOLDER -> WizardStep.PERMISSIONS
            WizardStep.TEST_CALL -> WizardStep.RECORDING_FOLDER
            WizardStep.TEST_RESULT -> WizardStep.TEST_CALL
        }
        _uiState.update { it.copy(currentStep = prev) }
    }

    fun toggleAdvancedDiagnostics(show: Boolean? = null) {
        _uiState.update { it.copy(showAdvancedDiagnostics = show ?: !it.showAdvancedDiagnostics) }
    }

    fun resetTestState() {
        _uiState.update {
            it.copy(
                recentCallEvent = null,
                gateResult = null,
                candidateRecordings = emptyList(),
                matchingResult = null,
                isCallSimulated = false,
                isSearchingRecording = false,
                callLogErrorMessage = null,
                uploadState = UploadState.Idle,
                showUploadConfirmation = false,
                currentStep = WizardStep.TEST_CALL
            )
        }
    }

    fun refreshState() {
        val permissions = diagnosticsManager.checkPermissions()
        val safUri = getSavedSafUri()
        val safConfigured = safUri != null
        val safValid = safRecordingSource.isAccessible()
        val mediaStoreAccessible = mediaStoreRecordingSource.isAccessible()
        val callLogStatus = callLogReader.checkCallLogAccess()
        val callLogDiag = callLogReader.diagnoseCallLogAccess()

        // Auto-start CallStateObserver if phone state permission is granted and not yet listening
        if (callStateObserver.hasPhoneStatePermission() && !callStateObserver.isObserving()) {
            callStateObserver.startObserving()
        }

        val safInspection = if (safConfigured && safValid) {
            safRecordingSource.inspectFolderContent()
        } else null

        val hasTestCall = _uiState.value.recentCallEvent != null
        val hasMatch = _uiState.value.matchingResult?.status == MatchStatus.FILE_FOUND
        val hasUpload = _uiState.value.uploadState is UploadState.Success

        val overall = diagnosticsManager.computeCompatibilityStatus(
            hasTestCallRun = hasTestCall,
            hasSuccessfulMatch = hasMatch,
            hasUploadVerified = hasUpload
        )

        val currentLeads = callGateManager.getTestLeads()
        val currentSelectedId = _uiState.value.selectedLeadId
        val validSelectedId = currentSelectedId?.takeIf { id -> currentLeads.any { it.id == id } }
            ?: currentLeads.firstOrNull()?.id

        _uiState.update { current ->
            current.copy(
                permissions = permissions,
                callLogAccessStatus = callLogStatus,
                callLogDiagnostic = callLogDiag,
                safUriString = safUri?.toString(),
                safConfigured = safConfigured,
                safValid = safValid,
                safInspection = safInspection,
                mediaStoreAccessible = mediaStoreAccessible,
                overallStatus = overall,
                testLeads = currentLeads,
                selectedLeadId = validSelectedId,
                auditLogs = callGateManager.getAuditTrace()
            )
        }
    }

    fun setDialerRecordingConfirmed(confirmed: Boolean) {
        prefs.edit().putBoolean(KEY_DIALER_RECORDING_CONFIRMED, confirmed).apply()
        _uiState.update { it.copy(dialerRecordingConfirmed = confirmed) }
    }

    /**
     * Called on MainActivity onResume or when user explicitly taps "Check for Recent Call".
     * Inspects the CallLog for calls that ended while the app was in the background.
     */
    fun checkForRecentCall() {
        if (!callLogReader.hasCallLogPermission()) {
            _uiState.update {
                it.copy(callLogErrorMessage = "Call Log permission (READ_CALL_LOG) is missing. Tap 'Grant Required Permissions' in Step 1 or allow it in Android Settings.")
            }
            return
        }

        if (!callStateObserver.hasPhoneStatePermission()) {
            _uiState.update {
                it.copy(callLogErrorMessage = "Phone State permission (READ_PHONE_STATE) is missing. Call connect/disconnect events cannot be observed.")
            }
            return
        }

        val accessStatus = callLogReader.checkCallLogAccess()
        if (accessStatus == CallLogAccessStatus.RESTRICTED_BY_PLATFORM) {
            _uiState.update {
                it.copy(callLogErrorMessage = "Call Log access is restricted by Android system or device privacy guard. Check App Info -> Permissions.")
            }
            return
        }

        val call = callStateObserver.checkRecentCompletedCall(maxEndRecencyMs = 900_000L)
        if (call == null) {
            val selected = _uiState.value.selectedLead
            val targetMsg = if (selected != null) {
                " with configured lead ${PrivacyRedactor.redactPhoneNumber(selected.normalizedNumber)} (or any incoming call)"
            } else " with a lead"
            _uiState.update {
                it.copy(callLogErrorMessage = "No recent completed call found in system Call Log (last 15m). Ensure you made or received a call$targetMsg on this phone's SIM, ended the call, and verified that native auto-recording is ON in dialer settings.")
            }
        } else {
            _uiState.update { it.copy(callLogErrorMessage = null) }
        }
    }

    fun setSafTreeUri(uri: Uri) {
        try {
            SafRecordingSource.takePersistablePermissions(context, uri)
            prefs.edit().putString(KEY_SAF_URI, uri.toString()).apply()
            context.getSharedPreferences("com.leads365.apk.preferences", Context.MODE_PRIVATE)
                .edit().putString(KEY_SAF_URI, uri.toString()).apply()
            context.getSharedPreferences("leads365_prefs", Context.MODE_PRIVATE)
                .edit().putString("saf_tree_uri", uri.toString()).apply()

            safRecordingSource.notifyFolderUpdated()

            _uiState.update {
                it.copy(
                    folderQuerySuccess = null,
                    folderQueryMessage = ""
                )
            }
        } catch (e: Exception) {
            // Error persisting permissions
        }
        refreshState()
        verifyFolderAccess()
    }

    fun clearSafTreeUri() {
        prefs.edit().remove(KEY_SAF_URI).apply()
        context.getSharedPreferences("com.leads365.apk.preferences", Context.MODE_PRIVATE)
            .edit().remove(KEY_SAF_URI).apply()
        context.getSharedPreferences("leads365_prefs", Context.MODE_PRIVATE)
            .edit().remove("saf_tree_uri").apply()

        safRecordingSource.notifyFolderUpdated()

        _uiState.update {
            it.copy(
                folderQuerySuccess = null,
                folderQueryMessage = "",
                safInspection = null
            )
        }
        refreshState()
    }

    fun inspectSafFolder() {
        if (safRecordingSource.isConfigured() && safRecordingSource.isAccessible()) {
            val inspection = safRecordingSource.inspectFolderContent()
            _uiState.update {
                it.copy(
                    safInspection = inspection,
                    folderQuerySuccess = inspection.audioFilesCount > 0,
                    folderQueryMessage = inspection.messageEn
                )
            }
        }
    }

    fun verifyFolderAccess() {
        viewModelScope.launch {
            val method = _uiState.value.activeDiscoveryMethod
            if (method == RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK) {
                if (!safRecordingSource.isAccessible()) {
                    _uiState.update {
                        it.copy(
                            folderQuerySuccess = false,
                            folderQueryMessage = "Folder permission is not granted or has expired."
                        )
                    }
                    return@launch
                }

                val inspection = safRecordingSource.inspectFolderContent()
                _uiState.update {
                    it.copy(
                        safInspection = inspection,
                        folderQuerySuccess = inspection.audioFilesCount > 0,
                        folderQueryMessage = inspection.messageEn
                    )
                }
            } else {
                if (!mediaStoreRecordingSource.isAccessible()) {
                    _uiState.update {
                        it.copy(
                            folderQuerySuccess = false,
                            folderQueryMessage = "MediaStore audio permission is not granted."
                        )
                    }
                    return@launch
                }

                try {
                    val now = System.currentTimeMillis()
                    val sampleCandidates = mediaStoreRecordingSource.queryCandidates(
                        callStartEpochMs = now - 86400_000L,
                        callEndEpochMs = now,
                        callDurationSec = 30L,
                        normalizedNumber = ""
                    )
                    _uiState.update {
                        it.copy(
                            folderQuerySuccess = true,
                            folderQueryMessage = "MediaStore audio accessible. Found ${sampleCandidates.size} audio file(s) in the last 24h."
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            folderQuerySuccess = false,
                            folderQueryMessage = "Error reading MediaStore: ${e.message ?: "Unknown I/O error"}"
                        )
                    }
                }
            }
        }
    }

    private fun getSavedSafUri(): Uri? {
        val raw = prefs.getString(KEY_SAF_URI, null) ?: return null
        return try {
            Uri.parse(raw)
        } catch (e: Exception) {
            null
        }
    }

    fun setActiveDiscoveryMethod(method: RecordingDiscoveryMethod) {
        _uiState.update { it.copy(activeDiscoveryMethod = method) }
        verifyFolderAccess()
    }

    fun selectLead(leadId: String) {
        prefs.edit().putString(KEY_SELECTED_LEAD_ID, leadId).apply()
        _uiState.update { it.copy(selectedLeadId = leadId) }
    }

    fun addTestLead(name: String, rawNumber: String, isDnr: Boolean = false): Result<TestLead> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return Result.failure(IllegalArgumentException("Lead name is required."))
        }
        val trimmedNumber = rawNumber.trim()
        if (!normalizer.isValid(trimmedNumber)) {
            return Result.failure(IllegalArgumentException("Enter a valid 10-digit Indian phone number (+91...)."))
        }
        val candidateNorm = normalizer.normalizeToE164(trimmedNumber) ?: trimmedNumber
        val candidateLast10 = normalizer.extractLast10Digits(candidateNorm)
        val existingLeads = callGateManager.getTestLeads()
        val isDuplicate = existingLeads.any { existing ->
            val existingNorm = existing.normalizedNumber
            val existingLast10 = normalizer.extractLast10Digits(existingNorm)
            existingNorm == candidateNorm || (candidateLast10.isNotEmpty() && candidateLast10 == existingLast10)
        }
        if (isDuplicate) {
            return Result.failure(IllegalArgumentException("A test lead with this phone number already exists."))
        }

        val id = "lead_${System.currentTimeMillis()}"
        val lead = TestLead(
            id = id,
            name = trimmedName,
            rawNumber = trimmedNumber,
            normalizedNumber = candidateNorm,
            isDoNotRecord = isDnr
        )
        callGateManager.addTestLead(lead)
        persistLeads(callGateManager.getTestLeads())
        selectLead(lead.id)
        refreshState()
        return Result.success(lead)
    }

    fun removeTestLead(leadId: String) {
        callGateManager.removeTestLead(leadId)
        val remaining = callGateManager.getTestLeads()
        persistLeads(remaining)
        if (_uiState.value.selectedLeadId == leadId) {
            val next = remaining.firstOrNull()?.id
            selectLead(next ?: "")
        }
        refreshState()
    }

    fun toggleDnr(leadId: String, dnr: Boolean) {
        callGateManager.toggleDoNotRecord(leadId, dnr)
        persistLeads(callGateManager.getTestLeads())
        refreshState()
    }

    private fun persistLeads(leads: List<TestLead>) {
        prefs.edit().putString(KEY_SAVED_LEADS, serializeLeads(leads)).apply()
    }

    fun setUploadEndpointUrl(url: String) {
        prefs.edit().putString(KEY_UPLOAD_ENDPOINT, url).apply()
        _uiState.update { it.copy(uploadEndpointUrl = url) }
    }

    /**
     * Complete Call Handling Pipeline:
     * Call Event -> Lead-Only Gate -> If Allowed -> Recording Source Query -> Matcher
     */
    fun handleCallEnded(call: CallEvent, isSimulated: Boolean) {
        val currentGate = _uiState.value.gateResult
        val currentCall = _uiState.value.recentCallEvent
        // If this exact call was already allowed and is actively being processed, do not let duplicate callbacks cancel it
        if (currentGate is GateEvaluationResult.Allowed && currentCall != null &&
            currentCall.normalizedNumber == call.normalizedNumber &&
            kotlin.math.abs(currentCall.startTimeEpochMs - call.startTimeEpochMs) < 15_000L) {
            return
        }

        val gateResult = callGateManager.evaluateCall(call)

        _uiState.update {
            it.copy(
                recentCallEvent = call,
                gateResult = gateResult,
                isCallSimulated = isSimulated,
                callLogErrorMessage = null
            )
        }

        when (gateResult) {
            is GateEvaluationResult.Allowed -> {
                // Non-negotiable: ONLY allowed test lead proceeds to recording query!
                if (diagnosticsManager.isDialerSandboxed()) {
                    _uiState.update {
                        it.copy(
                            recordingDiscoveryState = RecordingDiscoveryState.RECORDING_UNAVAILABLE_ON_THIS_DEVICE,
                            candidateRecordings = emptyList(),
                            matchingResult = null
                        )
                    }
                    return
                }

                val hasSafAccess = safRecordingSource.isConfigured() && safRecordingSource.isAccessible()
                val hasMediaAccess = mediaStoreRecordingSource.isAccessible()

                if (!hasSafAccess && !hasMediaAccess) {
                    _uiState.update {
                        it.copy(
                            recordingDiscoveryState = RecordingDiscoveryState.FOLDER_ACCESS_REQUIRED,
                            candidateRecordings = emptyList(),
                            matchingResult = null
                        )
                    }
                    return
                }

                fun fetchCandidates(): List<RecordingCandidate> {
                    val list = mutableListOf<RecordingCandidate>()
                    if (hasSafAccess) {
                        list.addAll(
                            safRecordingSource.queryCandidates(
                                callStartEpochMs = call.startTimeEpochMs,
                                callEndEpochMs = System.currentTimeMillis(),
                                callDurationSec = call.durationSeconds,
                                normalizedNumber = gateResult.lead.normalizedNumber
                            )
                        )
                    }
                    // Fallback to MediaStore if SAF produced 0 candidates and MediaStore permission is available
                    if (list.isEmpty() && hasMediaAccess) {
                        list.addAll(
                            mediaStoreRecordingSource.queryCandidates(
                                callStartEpochMs = call.startTimeEpochMs,
                                callEndEpochMs = System.currentTimeMillis(),
                                callDurationSec = call.durationSeconds,
                                normalizedNumber = gateResult.lead.normalizedNumber
                            )
                        )
                    }
                    return list
                }

                var candidates = try {
                    fetchCandidates()
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            recordingDiscoveryState = RecordingDiscoveryState.RECORDING_LOOKUP_FAILED,
                            candidateRecordings = emptyList(),
                            matchingResult = null
                        )
                    }
                    return
                }

                var matchResult = matcher.match(candidates, call)
                var resolvedState = when (matchResult.status) {
                    MatchStatus.FILE_FOUND -> RecordingDiscoveryState.RECORDING_FOUND
                    MatchStatus.AMBIGUOUS -> RecordingDiscoveryState.MULTIPLE_POSSIBLE_RECORDINGS
                    MatchStatus.NO_FILE_FOUND -> RecordingDiscoveryState.NO_RECORDING_FOUND
                    MatchStatus.INACCESSIBLE -> RecordingDiscoveryState.RECORDING_UNAVAILABLE_OR_INACCESSIBLE
                }

                _uiState.update {
                    it.copy(
                        candidateRecordings = candidates,
                        matchingResult = matchResult,
                        recordingDiscoveryState = resolvedState
                    )
                }

                // If no file found initially on a real call, poll progressively for delayed OEM dialer flush (Vivo/Xiaomi/Samsung)
                if (matchResult.status == MatchStatus.NO_FILE_FOUND && !isSimulated) {
                    viewModelScope.launch {
                        _uiState.update {
                            it.copy(
                                isSearchingRecording = true,
                                recordingDiscoveryState = RecordingDiscoveryState.WAITING_FOR_RECORDING
                            )
                        }
                        val delays = listOf(2000L, 3000L, 4000L, 5000L)
                        for (delayMs in delays) {
                            delay(delayMs)
                            candidates = fetchCandidates()
                            matchResult = matcher.match(candidates, call)
                            if (matchResult.status != MatchStatus.NO_FILE_FOUND) {
                                break
                            }
                        }
                        val finalState = when (matchResult.status) {
                            MatchStatus.FILE_FOUND -> RecordingDiscoveryState.RECORDING_FOUND
                            MatchStatus.AMBIGUOUS -> RecordingDiscoveryState.MULTIPLE_POSSIBLE_RECORDINGS
                            MatchStatus.NO_FILE_FOUND -> RecordingDiscoveryState.NO_RECORDING_FOUND
                            MatchStatus.INACCESSIBLE -> RecordingDiscoveryState.RECORDING_UNAVAILABLE_OR_INACCESSIBLE
                        }
                        _uiState.update {
                            it.copy(
                                isSearchingRecording = false,
                                candidateRecordings = candidates,
                                matchingResult = matchResult,
                                recordingDiscoveryState = finalState
                            )
                        }
                        refreshState()
                    }
                }
            }
            is GateEvaluationResult.RejectedNotALead,
            is GateEvaluationResult.RejectedDoNotRecord,
            is GateEvaluationResult.RejectedDuplicateCall -> {
                // HALT IMMEDIATELY. NO recording query, NO file access, NO upload!
                _uiState.update {
                    it.copy(
                        candidateRecordings = emptyList(),
                        matchingResult = null,
                        recordingDiscoveryState = RecordingDiscoveryState.IDLE
                    )
                }
            }
        }

        refreshState()
    }

    /**
     * Testing / Feasibility simulator for validation on devices without a live SIM call
     * Explicitly marked as SIMULATED to distinguish from physical phone dialer recordings.
     */
    fun simulateCall(scenario: SimulatedCallScenario) {
        val now = System.currentTimeMillis()
        val duration = 30L
        val callStart = now - (duration * 1000L) - 5_000L
        val callEnd = callStart + (duration * 1000L)

        when (scenario) {
            SimulatedCallScenario.MATCHED_TEST_LEAD -> {
                val currentSelected = _uiState.value.selectedLead
                val lead = if (currentSelected != null && !currentSelected.isDoNotRecord) {
                    currentSelected
                } else {
                    callGateManager.getTestLeads().firstOrNull { !it.isDoNotRecord }
                } ?: run {
                    val simLead = TestLead(
                        id = "lead_sim_default",
                        name = "Test Lead",
                        rawNumber = "+91 98765 43210",
                        normalizedNumber = "+919876543210",
                        isDoNotRecord = false
                    )
                    callGateManager.addTestLead(simLead)
                    persistLeads(callGateManager.getTestLeads())
                    selectLead(simLead.id)
                    simLead
                }

                val call = CallEvent(
                    id = "sim_${now}",
                    rawNumber = lead.rawNumber,
                    normalizedNumber = lead.normalizedNumber,
                    startTimeEpochMs = callStart,
                    endTimeEpochMs = callEnd,
                    durationSeconds = duration,
                    direction = CallDirection.OUTGOING
                )
                handleCallEnded(call, isSimulated = true)
                if (_uiState.value.candidateRecordings.isEmpty()) {
                    val simCandidate = RecordingCandidate(
                        uriString = "android.resource://${context.packageName}/raw/sample_rec",
                        displayName = "Call_${lead.normalizedNumber}_${callEnd}.m4a",
                        sizeBytes = 184_320L,
                        lastModifiedEpochMs = callEnd + 3_000L,
                        mimeType = "audio/m4a",
                        durationSeconds = duration,
                        sourceMethod = _uiState.value.activeDiscoveryMethod
                    )
                    val simMatch = matcher.match(listOf(simCandidate), call)
                    _uiState.update {
                        it.copy(
                            candidateRecordings = listOf(simCandidate),
                            matchingResult = simMatch
                        )
                    }
                    refreshState()
                }
            }
            SimulatedCallScenario.DO_NOT_RECORD_LEAD -> {
                val dnrLead = callGateManager.getTestLeads().firstOrNull { it.isDoNotRecord }
                    ?: run {
                        val tempDnr = TestLead("lead_sim_dnr", "DNR Test Lead", "+91 98765 43212", "+919876543212", isDoNotRecord = true)
                        callGateManager.addTestLead(tempDnr)
                        tempDnr
                    }
                val call = CallEvent(
                    id = "sim_dnr_${now}",
                    rawNumber = dnrLead.rawNumber,
                    normalizedNumber = dnrLead.normalizedNumber,
                    startTimeEpochMs = callStart,
                    endTimeEpochMs = callEnd,
                    durationSeconds = duration,
                    direction = CallDirection.OUTGOING
                )
                handleCallEnded(call, isSimulated = true)
            }
            SimulatedCallScenario.NON_LEAD_NUMBER -> {
                val call = CallEvent(
                    id = "sim_non_lead_${now}",
                    rawNumber = "+91 99999 11111",
                    normalizedNumber = "+919999911111",
                    startTimeEpochMs = callStart,
                    endTimeEpochMs = callEnd,
                    durationSeconds = duration,
                    direction = CallDirection.INCOMING
                )
                handleCallEnded(call, isSimulated = true)
            }
            SimulatedCallScenario.AMBIGUOUS_RECORDINGS -> {
                val currentSelected = _uiState.value.selectedLead
                val lead = if (currentSelected != null && !currentSelected.isDoNotRecord) {
                    currentSelected
                } else {
                    callGateManager.getTestLeads().firstOrNull { !it.isDoNotRecord }
                } ?: run {
                    val simLead = TestLead("lead_sim_default", "Test Lead", "+91 98765 43210", "+919876543210", isDoNotRecord = false)
                    callGateManager.addTestLead(simLead)
                    persistLeads(callGateManager.getTestLeads())
                    selectLead(simLead.id)
                    simLead
                }

                val call = CallEvent(
                    id = "sim_amb_${now}",
                    rawNumber = lead.rawNumber,
                    normalizedNumber = lead.normalizedNumber,
                    startTimeEpochMs = callStart,
                    endTimeEpochMs = callEnd,
                    durationSeconds = duration,
                    direction = CallDirection.OUTGOING
                )
                val cand1 = RecordingCandidate(
                    uriString = "android.resource://${context.packageName}/raw/cand1",
                    displayName = "Call_Recording_A.m4a",
                    sizeBytes = 180_000L,
                    lastModifiedEpochMs = callEnd + 4_000L,
                    mimeType = "audio/m4a",
                    durationSeconds = 29L,
                    sourceMethod = _uiState.value.activeDiscoveryMethod
                )
                val cand2 = RecordingCandidate(
                    uriString = "android.resource://${context.packageName}/raw/cand2",
                    displayName = "Call_Recording_B.m4a",
                    sizeBytes = 182_000L,
                    lastModifiedEpochMs = callEnd + 5_000L,
                    mimeType = "audio/m4a",
                    durationSeconds = 30L,
                    sourceMethod = _uiState.value.activeDiscoveryMethod
                )
                val candidates = listOf(cand1, cand2)
                val ambMatch = matcher.match(candidates, call)
                _uiState.update {
                    it.copy(
                        recentCallEvent = call,
                        isCallSimulated = true,
                        gateResult = GateEvaluationResult.Allowed(lead, call),
                        candidateRecordings = candidates,
                        matchingResult = ambMatch
                    )
                }
                refreshState()
            }
        }
    }

    fun requestUploadConfirmation(candidate: RecordingCandidate) {
        val checksum = uploader.calculateSha256(candidate.uri) ?: "simulated_sha256_${candidate.displayName.hashCode()}"
        _uiState.update {
            it.copy(
                showUploadConfirmation = true,
                selectedCandidateForUpload = candidate,
                selectedCandidateChecksum = checksum
            )
        }
    }

    fun dismissUploadConfirmation() {
        _uiState.update {
            it.copy(
                showUploadConfirmation = false,
                selectedCandidateForUpload = null
            )
        }
    }

    fun executeConfirmedUpload() {
        val candidate = _uiState.value.selectedCandidateForUpload ?: return
        val endpoint = _uiState.value.uploadEndpointUrl

        _uiState.update { it.copy(showUploadConfirmation = false) }

        viewModelScope.launch {
            uploader.uploadTestRecording(
                candidate = candidate,
                endpointUrl = endpoint
            )
            refreshState()
        }
    }

    fun exportRedactedDiagnostics(): String {
        val report = DiagnosticsReport(
            deviceModel = diagnosticsManager.getDeviceModel(),
            manufacturer = diagnosticsManager.getManufacturer(),
            androidVersion = diagnosticsManager.getAndroidVersion(),
            sdkInt = diagnosticsManager.getSdkInt(),
            safConfigured = safRecordingSource.isConfigured(),
            safTreeUriValid = safRecordingSource.isAccessible(),
            mediaStoreAudioAccessible = mediaStoreRecordingSource.isAccessible(),
            phoneStatePermissionGranted = callStateObserver.hasPhoneStatePermission(),
            callLogPermissionGranted = callLogReader.hasCallLogPermission(),
            totalTestLeadsConfigured = callGateManager.getTestLeads().size,
            lastGateResult = _uiState.value.gateResult?.javaClass?.simpleName,
            lastMatchingStatus = _uiState.value.matchingResult?.status?.name,
            lastDiscoveryDelaySec = 3L,
            overallCompatibility = _uiState.value.overallStatus,
            reportTimestampEpochMs = System.currentTimeMillis()
        )
        return diagnosticsManager.generateRedactedDiagnosticJson(report, callGateManager.getAuditTrace())
    }

    companion object {
        private const val PREFS_NAME = "leads365_phase1_prefs"
        private const val KEY_SAF_URI = "key_saf_tree_uri"
        private const val KEY_UPLOAD_ENDPOINT = "key_test_upload_endpoint"
        private const val KEY_SAVED_LEADS = "key_saved_test_leads_json"
        private const val KEY_SELECTED_LEAD_ID = "key_selected_lead_id"
        private const val KEY_DIALER_RECORDING_CONFIRMED = "key_dialer_recording_confirmed"

        fun serializeLeads(leads: List<TestLead>): String {
            val array = org.json.JSONArray()
            for (lead in leads) {
                val obj = org.json.JSONObject()
                obj.put("id", lead.id)
                obj.put("name", lead.name)
                obj.put("rawNumber", lead.rawNumber)
                obj.put("normalizedNumber", lead.normalizedNumber)
                obj.put("isDoNotRecord", lead.isDoNotRecord)
                obj.put("notes", lead.notes)
                obj.put("enabled", lead.enabled)
                array.put(obj)
            }
            return array.toString()
        }

        fun deserializeLeads(jsonStr: String): List<TestLead> {
            if (jsonStr.isBlank()) return emptyList()
            return try {
                val array = org.json.JSONArray(jsonStr)
                val list = mutableListOf<TestLead>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        TestLead(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            rawNumber = obj.getString("rawNumber"),
                            normalizedNumber = obj.getString("normalizedNumber"),
                            isDoNotRecord = obj.optBoolean("isDoNotRecord", false),
                            notes = obj.optString("notes", ""),
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

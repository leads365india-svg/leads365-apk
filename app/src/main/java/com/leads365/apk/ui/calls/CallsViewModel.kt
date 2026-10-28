package com.leads365.apk.ui.calls

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leads365.apk.core.RecordingCandidate
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.recordings.MediaStoreRecordingSource
import com.leads365.apk.recordings.SafRecordingSource
import com.leads365.apk.work.RecordingUploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CallFilterTab(val label: String) {
    ALL("All Calls"),
    WITH_RECORDINGS("Recordings"),
    LEAD_CALLS("Assigned Leads"),
    UNLINKED("Unlinked Inbound")
}

data class CallRecordUiModel(
    val id: String,
    val call: CallEntity? = null,
    val candidate: RecordingCandidate? = null,
    val uploadTask: UploadTaskEntity? = null,
    val isUnlinkedInbound: Boolean = false,
    val hasRecording: Boolean = false,
    val recordingFileName: String? = null,
    val recordingFileSizeBytes: Long? = null,
    val recordingUri: String? = null,
    val isCandidateOnly: Boolean = false,
    val discoveryState: String = "Matched" // "Matched", "Unmatched", "Inaccessible"
)

@HiltViewModel
class CallsViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val safRecordingSource: SafRecordingSource,
    private val mediaStoreRecordingSource: MediaStoreRecordingSource,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow(CallFilterTab.ALL)
    val selectedFilter: StateFlow<CallFilterTab> = _selectedFilter.asStateFlow()

    private val _isRecordingAccessAvailable = MutableStateFlow(
        safRecordingSource.isAccessible() || mediaStoreRecordingSource.isAccessible()
    )
    val isRecordingAccessAvailable: StateFlow<Boolean> = _isRecordingAccessAvailable.asStateFlow()

    private val _isFolderConfigured = MutableStateFlow(
        safRecordingSource.isConfigured()
    )
    val isFolderConfigured: StateFlow<Boolean> = _isFolderConfigured.asStateFlow()

    private val _discoveredCandidates = MutableStateFlow<List<RecordingCandidate>>(emptyList())
    val discoveredCandidates: StateFlow<List<RecordingCandidate>> = _discoveredCandidates.asStateFlow()

    init {
        refreshDiscoveredRecordings()
    }

    fun refreshDiscoveredRecordings() {
        refreshAccessState()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val safList = if (safRecordingSource.isAccessible()) {
                    safRecordingSource.listDiscoveredRecordings()
                } else emptyList()

                val mediaStoreList = if (safList.isEmpty() && mediaStoreRecordingSource.isAccessible()) {
                    mediaStoreRecordingSource.listDiscoveredRecordings()
                } else emptyList()

                _discoveredCandidates.value = (safList + mediaStoreList).distinctBy { it.uriString }
            } catch (e: Exception) {
                Log.w("CallsViewModel", "Error loading discovered recordings: ${e.message}")
            }
        }
    }

    // Combine all calls from Room with all upload tasks from Room
    private val allCallRecords = combine(
        callRepository.getAllCallsFlow(),
        uploadQueueRepository.getAllTasksFlow()
    ) { calls, tasks ->
        val taskMapByCallId = tasks.filter { !it.callId.isNullOrBlank() }
            .associateBy { it.callId!! }

        calls.map { call ->
            val task = taskMapByCallId[call.id] ?: taskMapByCallId[call.clientCallId]
            val hasRec = call.hasRecording || task != null || !call.recordingUri.isNullOrBlank()
            val fileName = call.recordingFileName ?: task?.fileName
            val fileSize = call.recordingFileSizeBytes ?: task?.fileSizeBytes
            val uri = call.recordingUri ?: task?.localFileUri
            val isUnlinked = !call.isLeadCall || call.leadId.isNullOrBlank()

            CallRecordUiModel(
                id = call.id,
                call = call,
                uploadTask = task,
                isUnlinkedInbound = isUnlinked,
                hasRecording = hasRec,
                recordingFileName = fileName,
                recordingFileSizeBytes = fileSize,
                recordingUri = uri,
                isCandidateOnly = false,
                discoveryState = if (hasRec) "Matched" else "No File Found"
            )
        }
    }

    // Unified call & recording list based on active filter
    val callItems: StateFlow<List<CallRecordUiModel>> = combine(
        allCallRecords,
        _discoveredCandidates,
        _selectedFilter
    ) { callRecords, candidates, filter ->
        val knownUris = callRecords.mapNotNull { it.recordingUri }.toSet()
        val knownNames = callRecords.mapNotNull { it.recordingFileName }.toSet()

        val unmatchedCandidates = candidates.filter { c ->
            c.uriString !in knownUris && c.displayName !in knownNames
        }.map { c ->
            CallRecordUiModel(
                id = "cand_${c.uriString.hashCode()}",
                call = null,
                candidate = c,
                uploadTask = null,
                isUnlinkedInbound = false,
                hasRecording = true,
                recordingFileName = c.displayName,
                recordingFileSizeBytes = c.sizeBytes,
                recordingUri = c.uriString,
                isCandidateOnly = true,
                discoveryState = if (!c.isReadable) "Inaccessible" else "Unmatched"
            )
        }

        when (filter) {
            CallFilterTab.ALL -> callRecords
            CallFilterTab.WITH_RECORDINGS -> {
                val matchedWithAudio = callRecords.filter { it.hasRecording }
                matchedWithAudio + unmatchedCandidates
            }
            CallFilterTab.LEAD_CALLS -> callRecords.filter { !it.isUnlinkedInbound }
            CallFilterTab.UNLINKED -> callRecords.filter { it.isUnlinkedInbound }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allCount: StateFlow<Int> = allCallRecords.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recordingsCount: StateFlow<Int> = combine(
        allCallRecords,
        _discoveredCandidates
    ) { calls, candidates ->
        val matchedCount = calls.count { it.hasRecording }
        if (matchedCount > 0) {
            val knownUris = calls.mapNotNull { it.recordingUri }.toSet()
            val unmatchedCount = candidates.count { it.uriString !in knownUris }
            matchedCount + unmatchedCount
        } else {
            candidates.size
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val unlinkedCount: StateFlow<Int> = allCallRecords.map { items -> items.count { it.isUnlinkedInbound } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val leadCount: StateFlow<Int> = allCallRecords.map { items -> items.count { !it.isUnlinkedInbound } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Raw calls list
    val calls: StateFlow<List<CallEntity>> = callRepository.getAllCallsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setFilter(filter: CallFilterTab) {
        _selectedFilter.value = filter
        refreshDiscoveredRecordings()
    }

    fun refreshAccessState() {
        _isRecordingAccessAvailable.value = safRecordingSource.isAccessible() || mediaStoreRecordingSource.isAccessible()
        _isFolderConfigured.value = safRecordingSource.isConfigured()
    }

    fun retryUpload(task: UploadTaskEntity) {
        viewModelScope.launch {
            uploadQueueRepository.updateStatus(task.id, UploadTaskEntity.STATE_FILE_FOUND, null)
            RecordingUploadWorker.enqueueUniqueUpload(context, task.id, task.callId)
        }
    }

    fun playRecording(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("CallsViewModel", "Failed to launch native audio player: ${e.message}")
            false
        }
    }
}

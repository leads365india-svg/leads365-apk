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
        viewModelScope.launch {
            safRecordingSource.folderUpdateEvents.collect {
                refreshDiscoveredRecordings()
            }
        }
    }

    fun refreshDiscoveredRecordings(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {
        refreshAccessState()
        viewModelScope.launch(dispatcher) {
            try {
                val safList = if (safRecordingSource.isAccessible()) {
                    safRecordingSource.listDiscoveredRecordings()
                } else emptyList()

                val mediaStoreList = if (safList.isEmpty() && mediaStoreRecordingSource.isAccessible()) {
                    mediaStoreRecordingSource.listDiscoveredRecordings()
                } else emptyList()

                val candidates = (safList + mediaStoreList).distinctBy { it.uriString }
                _discoveredCandidates.value = candidates
                refreshAccessState()

                // Reconcile discovered audio files with calls in Room that lack recordings
                try {
                    callRepository.reconcileUnmatchedRecordings(candidates)
                } catch (e: Exception) {
                    Log.w("CallsViewModel", "Error reconciling unmatched recordings: ${e.message}")
                }

                try {
                    callRepository.syncCallsFromBackend()
                } catch (ignored: Exception) {}
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
        mapCallRecords(calls, tasks)
    }

    // Unified call & recording list based on active filter
    val callItems: StateFlow<List<CallRecordUiModel>> = combine(
        allCallRecords,
        _discoveredCandidates,
        _selectedFilter
    ) { callRecords, candidates, filter ->
        filterCallItems(callRecords, candidates, filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    val allCount: StateFlow<Int> = allCallRecords.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val unlinkedCount: StateFlow<Int> = allCallRecords.map { items -> items.count { it.isUnlinkedInbound } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val leadCount: StateFlow<Int> = allCallRecords.map { items -> items.count { !it.isUnlinkedInbound } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Raw calls list
    val calls: StateFlow<List<CallEntity>> = callRepository.getAllCallsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
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

    suspend fun executeUploadRecording(callId: String) {
        val call = (callRepository.getCallById(callId) as? com.leads365.apk.core.AppResult.Success)?.data
            ?: (callRepository.getByClientCallId(callId) as? com.leads365.apk.core.AppResult.Success)?.data

        val targetCallId = call?.clientCallId ?: call?.id ?: callId

        var task = (uploadQueueRepository.getTaskByCallId(targetCallId) as? com.leads365.apk.core.AppResult.Success)?.data
            ?: (uploadQueueRepository.getTaskByCallId(callId) as? com.leads365.apk.core.AppResult.Success)?.data
            ?: (call?.recordingUri?.let { (uploadQueueRepository.getTaskByLocalFileUri(it) as? com.leads365.apk.core.AppResult.Success)?.data })

        if (task == null && call != null && !call.recordingUri.isNullOrBlank()) {
            val newTask = UploadTaskEntity(
                id = java.util.UUID.randomUUID().toString(),
                callId = targetCallId,
                leadId = call.leadId ?: "unlinked",
                localFileUri = call.recordingUri!!,
                fileName = call.recordingFileName ?: "recording_${System.currentTimeMillis()}.m4a",
                fileSizeBytes = call.recordingFileSizeBytes ?: 0L,
                status = UploadTaskEntity.STATUS_PENDING
            )
            uploadQueueRepository.enqueueTask(newTask)
            task = newTask
        }

        if (task != null) {
            uploadQueueRepository.updateStatus(task.id, UploadTaskEntity.STATUS_PENDING, null)
            RecordingUploadWorker.enqueueUniqueUpload(
                context = context,
                taskId = task.id,
                callId = targetCallId,
                policy = androidx.work.ExistingWorkPolicy.REPLACE
            )
        }
    }

    fun uploadRecording(callId: String, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {
        viewModelScope.launch(dispatcher) {
            executeUploadRecording(callId)
        }
    }

    suspend fun executeRetryUpload(task: UploadTaskEntity) {
        uploadQueueRepository.updateStatus(task.id, UploadTaskEntity.STATUS_PENDING, null)
        RecordingUploadWorker.enqueueUniqueUpload(
            context = context,
            taskId = task.id,
            callId = task.callId,
            policy = androidx.work.ExistingWorkPolicy.REPLACE
        )
    }

    fun retryUpload(task: UploadTaskEntity, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {
        viewModelScope.launch(dispatcher) {
            executeRetryUpload(task)
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
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "No media player app installed to play audio.", android.widget.Toast.LENGTH_SHORT).show()
            Log.e("CallsViewModel", "No app available to play audio: ${e.message}")
            false
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Unable to play audio: ${e.message ?: "File unreadable"}", android.widget.Toast.LENGTH_SHORT).show()
            Log.e("CallsViewModel", "Failed to launch native audio player: ${e.message}")
            false
        }
    }

    companion object {
        fun mapCallRecords(calls: List<CallEntity>, tasks: List<UploadTaskEntity>): List<CallRecordUiModel> {
            val taskMapByCallId = tasks.filter { !it.callId.isNullOrBlank() }
                .associateBy { it.callId!! }
            val taskMapByUri = tasks.filter { !it.localFileUri.isNullOrBlank() }
                .associateBy { it.localFileUri }

            return calls.map { call ->
                val task = taskMapByCallId[call.id] 
                    ?: taskMapByCallId[call.clientCallId]
                    ?: (call.recordingUri?.let { taskMapByUri[it] })
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

        fun filterCallItems(
            callRecords: List<CallRecordUiModel>,
            candidates: List<RecordingCandidate>,
            filter: CallFilterTab
        ): List<CallRecordUiModel> {
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

            return when (filter) {
                CallFilterTab.ALL -> callRecords
                CallFilterTab.WITH_RECORDINGS -> {
                    val matchedWithAudio = callRecords.filter { it.hasRecording }
                    matchedWithAudio + unmatchedCandidates
                }
                CallFilterTab.LEAD_CALLS -> callRecords.filter { !it.isUnlinkedInbound }
                CallFilterTab.UNLINKED -> callRecords.filter { it.isUnlinkedInbound }
            }
        }
    }
}

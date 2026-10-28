package com.leads365.apk.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.recordings.MediaStoreRecordingSource
import com.leads365.apk.recordings.SafRecordingSource
import com.leads365.apk.work.WorkManagerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val leadRepository: LeadRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val safRecordingSource: SafRecordingSource,
    private val mediaStoreRecordingSource: MediaStoreRecordingSource,
    private val authRepository: AuthRepository,
    private val workManagerScheduler: WorkManagerScheduler,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    val calls: StateFlow<List<CallEntity>> = callRepository.getAllCallsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val leadsCount: StateFlow<Int> = leadRepository.getAllLeadsFlow()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val uploadTasks: StateFlow<List<UploadTaskEntity>> = uploadQueueRepository.getAllTasksFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isRecordingAccessAvailable = MutableStateFlow(
        safRecordingSource.isAccessible() || mediaStoreRecordingSource.isAccessible()
    )
    val isRecordingAccessAvailable: StateFlow<Boolean> = _isRecordingAccessAvailable.asStateFlow()

    private val _discoveredRecordingsCount = MutableStateFlow(0)
    val discoveredRecordingsCount: StateFlow<Int> = _discoveredRecordingsCount.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(leadRepository.getLastSyncTimestamp())
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private val _isSyncStale = MutableStateFlow(false)
    val isSyncStale: StateFlow<Boolean> = _isSyncStale.asStateFlow()

    init {
        refreshReadiness()
        viewModelScope.launch {
            safRecordingSource.folderUpdateEvents.collect {
                refreshReadiness()
            }
        }
    }

    fun refreshReadiness(dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO) {
        _isRecordingAccessAvailable.value = safRecordingSource.isAccessible() || mediaStoreRecordingSource.isAccessible()
        val lastSync = leadRepository.getLastSyncTimestamp()
        _lastSyncTimestamp.value = lastSync
        val staleThresholdMs = 4 * 60 * 60 * 1000L // 4 hours
        _isSyncStale.value = authRepository.isLoggedIn() && (lastSync > 0L && (System.currentTimeMillis() - lastSync) > staleThresholdMs)

        viewModelScope.launch(dispatcher) {
            val count = if (safRecordingSource.isAccessible()) {
                val list = safRecordingSource.listDiscoveredRecordings()
                if (list.isNotEmpty()) list.size else (safRecordingSource.inspectFolderContent()?.audioFilesCount ?: 0)
            } else if (mediaStoreRecordingSource.isAccessible()) {
                mediaStoreRecordingSource.listDiscoveredRecordings().size
            } else 0
            _discoveredRecordingsCount.value = count
            _isRecordingAccessAvailable.value = safRecordingSource.isAccessible() || mediaStoreRecordingSource.isAccessible()
        }
    }

    fun syncNow() {
        refreshReadiness()
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                if (authRepository.isLoggedIn()) {
                    leadRepository.syncLeads()
                    val newSync = leadRepository.getLastSyncTimestamp()
                    _lastSyncTimestamp.value = newSync
                    _isSyncStale.value = false
                    workManagerScheduler.triggerOneTimeHeartbeat()
                }
            } catch (e: Exception) {
                // Non-blocking sync error
            } finally {
                _isSyncing.value = false
            }
        }
    }
}

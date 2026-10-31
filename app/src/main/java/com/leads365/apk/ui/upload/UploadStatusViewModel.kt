package com.leads365.apk.ui.upload

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.network.NetworkConnectivityMonitor
import com.leads365.apk.work.RecordingUploadWorker
import com.leads365.apk.work.WorkManagerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadStatusViewModel @Inject constructor(
    private val uploadQueueRepository: UploadQueueRepository,
    @ApplicationContext private val context: Context,
    private val workManagerScheduler: WorkManagerScheduler? = null,
    private val networkConnectivityMonitor: NetworkConnectivityMonitor? = null
) : ViewModel() {

    private val prefs = context.getSharedPreferences(RecordingUploadWorker.PREFS_NAME, Context.MODE_PRIVATE)

    private val _wifiOnly = MutableStateFlow(
        prefs.getBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, false)
    )
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    val isWifiConnected: StateFlow<Boolean> = networkConnectivityMonitor?.isWifiConnected
        ?: MutableStateFlow(false).asStateFlow()

    val tasks: StateFlow<List<UploadTaskEntity>> = uploadQueueRepository.getAllTasksFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun isTaskEligibleForUpload(task: UploadTaskEntity): Boolean {
        return task.status == UploadTaskEntity.STATE_FILE_FOUND ||
                task.status == UploadTaskEntity.STATUS_PENDING ||
                task.status == UploadTaskEntity.STATE_RETRY_WAIT ||
                task.status == UploadTaskEntity.STATE_FAILED_PERMANENT ||
                task.status == UploadTaskEntity.STATE_REJECTED_BY_SERVER ||
                task.status == UploadTaskEntity.STATUS_FAILED
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, enabled).apply()
        _wifiOnly.value = enabled
        // Reschedule all pending and retryable uploads with the updated network constraints
        viewModelScope.launch {
            workManagerScheduler?.rescheduleAllPendingUploads()
        }
    }

    fun retryTask(task: UploadTaskEntity) {
        viewModelScope.launch {
            uploadQueueRepository.updateStatus(task.id, UploadTaskEntity.STATUS_PENDING, null)
            RecordingUploadWorker.enqueueUniqueUpload(
                context = context,
                taskId = task.id,
                callId = task.callId,
                policy = ExistingWorkPolicy.REPLACE
            )
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            uploadQueueRepository.clearCompleted()
        }
    }
}

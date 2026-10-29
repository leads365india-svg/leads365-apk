package com.leads365.apk.ui.upload

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.work.RecordingUploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadStatusViewModel @Inject constructor(
    private val uploadQueueRepository: UploadQueueRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = context.getSharedPreferences(RecordingUploadWorker.PREFS_NAME, Context.MODE_PRIVATE)

    private val _wifiOnly = MutableStateFlow(
        prefs.getBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, false)
    )
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _selectedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTaskIds: StateFlow<Set<String>> = _selectedTaskIds.asStateFlow()

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

    fun toggleTaskSelection(taskId: String) {
        _selectedTaskIds.update { current ->
            if (current.contains(taskId)) current - taskId else current + taskId
        }
    }

    fun selectAllEligible() {
        val eligibleIds = tasks.value.filter { isTaskEligibleForUpload(it) }.map { it.id }.toSet()
        _selectedTaskIds.value = eligibleIds
    }

    fun clearSelection() {
        _selectedTaskIds.value = emptySet()
    }

    fun uploadSelectedTasks() {
        val selected = _selectedTaskIds.value
        if (selected.isEmpty()) return
        val currentTasks = tasks.value.associateBy { it.id }

        viewModelScope.launch {
            for (taskId in selected) {
                val task = currentTasks[taskId] ?: continue
                if (!isTaskEligibleForUpload(task)) continue

                // Update to queued and enqueue unique WorkManager upload job
                uploadQueueRepository.updateStatus(task.id, UploadTaskEntity.STATE_FILE_FOUND, null)
                RecordingUploadWorker.enqueueUniqueUpload(context, task.id, task.callId)
            }
            clearSelection()
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(RecordingUploadWorker.PREF_UPLOAD_WIFI_ONLY, enabled).apply()
        _wifiOnly.value = enabled
    }

    fun retryTask(task: UploadTaskEntity) {
        viewModelScope.launch {
            uploadQueueRepository.updateStatus(task.id, UploadTaskEntity.STATE_FILE_FOUND, null)
            RecordingUploadWorker.enqueueUniqueUpload(context, task.id, task.callId)
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            uploadQueueRepository.clearCompleted()
        }
    }
}

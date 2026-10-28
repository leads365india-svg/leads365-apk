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

    val tasks: StateFlow<List<UploadTaskEntity>> = uploadQueueRepository.getAllTasksFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

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

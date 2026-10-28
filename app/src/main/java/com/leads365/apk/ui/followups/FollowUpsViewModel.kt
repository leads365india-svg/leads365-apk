package com.leads365.apk.ui.followups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.FollowUpRepository
import com.leads365.apk.database.entity.FollowUpEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

enum class FollowUpTab {
    TODAY,
    UPCOMING,
    OVERDUE
}

@HiltViewModel
class FollowUpsViewModel @Inject constructor(
    private val followUpRepository: FollowUpRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(FollowUpTab.TODAY)
    val selectedTab: StateFlow<FollowUpTab> = _selectedTab.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val followUpsState = followUpRepository.getPendingFollowUpsFlow()
        .combine(_selectedTab) { list, tab ->
            val now = System.currentTimeMillis()
            val (startOfToday, endOfToday) = getDayBounds()

            val today = list.filter { it.scheduledAtEpochMs in startOfToday..endOfToday }
                .sortedBy { it.scheduledAtEpochMs }
            val upcoming = list.filter { it.scheduledAtEpochMs > endOfToday }
                .sortedBy { it.scheduledAtEpochMs }
            val overdue = list.filter { it.scheduledAtEpochMs < now && it.scheduledAtEpochMs < startOfToday }
                .sortedBy { it.scheduledAtEpochMs }

            Triple(today, upcoming, overdue)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            Triple(emptyList(), emptyList(), emptyList())
        )

    init {
        refresh()
    }

    fun selectTab(tab: FollowUpTab) {
        _selectedTab.value = tab
    }

    fun refresh() {
        viewModelScope.launch {
            _isSyncing.value = true
            _errorMessage.value = null
            when (val result = followUpRepository.syncFollowUps()) {
                is AppResult.Success -> {
                    _isSyncing.value = false
                }
                is AppResult.Failure -> {
                    _isSyncing.value = false
                    _errorMessage.value = result.error.message
                }
            }
        }
    }

    fun completeFollowUp(id: String, notes: String = "") {
        viewModelScope.launch {
            followUpRepository.completeFollowUp(id, notes)
        }
    }

    fun rescheduleFollowUp(id: String, newScheduledAtEpochMs: Long, notes: String = "") {
        viewModelScope.launch {
            followUpRepository.rescheduleFollowUp(id, newScheduledAtEpochMs, notes)
        }
    }

    fun createFollowUp(
        leadId: String?,
        leadName: String,
        leadPhone: String,
        title: String,
        scheduledAtEpochMs: Long,
        priority: String = FollowUpEntity.PRIORITY_MEDIUM,
        notes: String = ""
    ) {
        viewModelScope.launch {
            followUpRepository.createFollowUp(
                leadId = leadId,
                leadName = leadName,
                leadPhone = leadPhone,
                title = title,
                scheduledAtEpochMs = scheduledAtEpochMs,
                priority = priority,
                notes = notes
            )
        }
    }

    private fun getDayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endOfToday = cal.timeInMillis

        return Pair(startOfToday, endOfToday)
    }
}

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
import java.util.TimeZone
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
        .combine(_selectedTab) { list, _ ->
            categorize(list)
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

    companion object {
        /**
         * Mutually exclusive, partition-safe pending follow-up categorization.
         *
         * - Overdue: scheduled time is strictly before current time (< now).
         * - Today: scheduled time is from current time through end of today (now <= scheduled <= endOfToday).
         * - Upcoming: scheduled time is strictly after end of today (> endOfToday).
         * - Completed/cancelled reminders must never appear in pending categories.
         *
         * Guarantees:
         * 1. Every pending reminder belongs to EXACTLY one category tab.
         * 2. Non-pending (completed, cancelled) are filtered out completely.
         * 3. Uses consistent timezone and day boundaries.
         */
        fun categorize(
            list: List<FollowUpEntity>,
            now: Long = System.currentTimeMillis(),
            timeZone: TimeZone = TimeZone.getDefault()
        ): Triple<List<FollowUpEntity>, List<FollowUpEntity>, List<FollowUpEntity>> {
            val (_, endOfToday) = getDayBounds(now, timeZone)

            // Completed or cancelled reminders must never appear in pending categories
            val pending = list.filter { it.status == FollowUpEntity.STATUS_PENDING }

            val overdue = pending.filter { it.scheduledAtEpochMs < now }
                .sortedBy { it.scheduledAtEpochMs }

            val today = pending.filter { it.scheduledAtEpochMs in now..endOfToday }
                .sortedBy { it.scheduledAtEpochMs }

            val upcoming = pending.filter { it.scheduledAtEpochMs > endOfToday }
                .sortedBy { it.scheduledAtEpochMs }

            return Triple(today, upcoming, overdue)
        }

        fun getDayBounds(
            epochMs: Long = System.currentTimeMillis(),
            timeZone: TimeZone = TimeZone.getDefault()
        ): Pair<Long, Long> {
            val cal = Calendar.getInstance(timeZone).apply {
                timeInMillis = epochMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfToday = cal.timeInMillis

            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val endOfToday = cal.timeInMillis

            return Pair(startOfToday, endOfToday)
        }

        fun isOverdue(
            item: FollowUpEntity,
            now: Long = System.currentTimeMillis()
        ): Boolean {
            return item.status == FollowUpEntity.STATUS_PENDING && item.scheduledAtEpochMs < now
        }
    }
}

package com.leads365.apk.ui.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.CachedPolicy
import com.leads365.apk.data.repository.PolicyRepository
import com.leads365.apk.database.entity.UploadTaskEntity
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.data.repository.LeadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrivacyUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val showConsentDialog: Boolean = false,
    val pauseToggleLoading: Boolean = false,
    val deletingLeadIds: Set<String> = emptySet()
)

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val policyRepository: PolicyRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val leadRepository: LeadRepository
) : ViewModel() {

    val policy: StateFlow<CachedPolicy> = policyRepository.policyState
    val consentAcknowledged: StateFlow<Boolean> = policyRepository.consentAcknowledged

    val uploadHistory: StateFlow<List<UploadTaskEntity>> = uploadQueueRepository.getAllTasksFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(PrivacyUiState())
    val uiState: StateFlow<PrivacyUiState> = _uiState.asStateFlow()

    init {
        refreshPolicy()
    }

    fun refreshPolicy() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = policyRepository.syncPolicy()) {
                is AppResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = null
                    )
                    // Show consent dialog if required and not yet acknowledged
                    if (result.data.requireConsentReminder && !policyRepository.isConsentAcknowledged()) {
                        _uiState.value = _uiState.value.copy(showConsentDialog = true)
                    }
                }
                is AppResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun togglePauseSync() {
        val currentPaused = policy.value.recordingSyncPaused
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pauseToggleLoading = true)
            when (val result = policyRepository.setPauseSyncState(!currentPaused)) {
                is AppResult.Success -> {
                    val newState = result.data
                    _uiState.value = _uiState.value.copy(
                        pauseToggleLoading = false,
                        successMessage = if (newState) "Recording sync paused" else "Recording sync resumed"
                    )
                }
                is AppResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        pauseToggleLoading = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun acknowledgeConsent() {
        policyRepository.acknowledgeConsent()
        _uiState.value = _uiState.value.copy(showConsentDialog = false)
    }

    fun dismissConsentDialog() {
        _uiState.value = _uiState.value.copy(showConsentDialog = false)
    }

    fun clearSuccessMessage() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Remove local lead data when the server has confirmed lead removal.
     * This is called during lead sync when deleted_lead_ids are received;
     * also exposed here for manual cleanup from the privacy screen.
     */
    fun removeLocalLeadData(leadId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                deletingLeadIds = _uiState.value.deletingLeadIds + leadId
            )
            leadRepository.deleteLeadById(leadId)
            _uiState.value = _uiState.value.copy(
                deletingLeadIds = _uiState.value.deletingLeadIds - leadId,
                successMessage = "Local lead data removed"
            )
        }
    }
}

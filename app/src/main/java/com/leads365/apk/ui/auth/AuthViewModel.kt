package com.leads365.apk.ui.auth

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leads365.apk.BuildConfig
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.data.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isPasswordVisible: Boolean = false,
    val showLogoutDialog: Boolean = false,
    val showUpdateRequiredDialog: Boolean = false,
    val updateMessage: String = ""
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val leadRepository: com.leads365.apk.data.repository.LeadRepository,
    private val callRepository: com.leads365.apk.data.repository.CallRepository,
    private val workManagerScheduler: com.leads365.apk.work.WorkManagerScheduler
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authState.collect { state ->
                when (state) {
                    is AuthState.Authenticated -> {
                        _uiState.update { it.copy(isLoading = false, errorMessage = null) }
                        // Automatically register device on authentication if not already registered
                        if (state.deviceId.isNullOrBlank()) {
                            registerCurrentDevice()
                        }
                    }
                    is AuthState.UpdateRequired -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                showUpdateRequiredDialog = true,
                                updateMessage = state.message
                            )
                        }
                    }
                    is AuthState.SessionRevoked -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = state.reason
                            )
                        }
                    }
                    is AuthState.Unauthenticated -> {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    is AuthState.Authenticating -> {
                        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                    }
                }
            }
        }
    }

    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email, errorMessage = null) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, errorMessage = null) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun login() {
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password

        if (email.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your email address.") }
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid email address.") }
            return
        }

        if (password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your password.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            when (val result = authRepository.login(email, password)) {
                is AppResult.Success -> {
                    registerCurrentDevice()
                    workManagerScheduler.triggerOneTimeLeadSync(forceFullSync = true)
                    workManagerScheduler.schedulePeriodicLeadSync()
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        }
    }

    fun registerCurrentDevice() {
        viewModelScope.launch {
            authRepository.registerDevice(
                deviceModel = Build.MODEL ?: "Unknown Device",
                manufacturer = Build.MANUFACTURER ?: "Android",
                osVersion = Build.VERSION.RELEASE ?: "Unknown OS",
                sdkInt = Build.VERSION.SDK_INT,
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME
            )
        }
    }

    fun showLogoutConfirmation(show: Boolean) {
        _uiState.update { it.copy(showLogoutDialog = show) }
    }

    fun logout(preservePendingUploads: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(showLogoutDialog = false, isLoading = true) }
            authRepository.logout(preservePendingUploads = preservePendingUploads)
            try {
                leadRepository.clearAllLeads()
                callRepository.clearAll()
            } catch (e: Exception) {
                // Non-fatal
            }
            _uiState.update { it.copy(isLoading = false, email = "", password = "") }
        }
    }
}

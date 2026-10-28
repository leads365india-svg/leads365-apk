package com.leads365.apk.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val selfTestRunner: SelfTestRunner
) : ViewModel() {

    private val _testResult = MutableStateFlow<SelfTestResult?>(null)
    val testResult: StateFlow<SelfTestResult?> = _testResult.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    init {
        runSelfTest()
    }

    fun runSelfTest() {
        if (_isRunning.value) return
        viewModelScope.launch {
            _isRunning.value = true
            try {
                val report = selfTestRunner.runFullDiagnostics()
                _testResult.value = report
            } catch (e: Exception) {
                // Safe error handling: wrap error in diagnostic item rather than crashing
                val fallbackItem = DiagnosticItem(
                    id = "diag_fatal_error",
                    title = "Self-Test Execution",
                    category = DiagnosticCategory.INTERNAL_LOGIC,
                    status = DiagnosticStatus.FAIL,
                    summary = "Diagnostics runner caught exception: ${e.message}",
                    actionableStep = "Retry self-test or restart application."
                )
                _testResult.value = SelfTestResult(
                    timestampEpochMs = System.currentTimeMillis(),
                    items = listOf(fallbackItem)
                )
            } finally {
                _isRunning.value = false
            }
        }
    }
}

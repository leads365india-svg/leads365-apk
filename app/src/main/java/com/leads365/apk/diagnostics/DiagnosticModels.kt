package com.leads365.apk.diagnostics

/**
 * Phase 11: Official Diagnostic Status Enum
 */
enum class DiagnosticStatus {
    PASS,
    WARNING,
    FAIL,
    NOT_TESTED
}

/**
 * Diagnostic functional categories
 */
enum class DiagnosticCategory {
    PERMISSIONS,
    CALL_DETECTION,
    RECORDING_STORAGE,
    CONNECTIVITY_SYNC,
    FCM_NOTIFICATIONS,
    PRIVACY_POLICY,
    INTERNAL_LOGIC
}

/**
 * Single diagnostic check item with actionable guidance
 */
data class DiagnosticItem(
    val id: String,
    val title: String,
    val category: DiagnosticCategory,
    val status: DiagnosticStatus,
    val summary: String,
    val actionableStep: String? = null
)

/**
 * Aggregated self-test run report
 */
data class SelfTestResult(
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val items: List<DiagnosticItem> = emptyList()
) {
    val passedCount: Int get() = items.count { it.status == DiagnosticStatus.PASS }
    val warningCount: Int get() = items.count { it.status == DiagnosticStatus.WARNING }
    val failedCount: Int get() = items.count { it.status == DiagnosticStatus.FAIL }
    val untestedCount: Int get() = items.count { it.status == DiagnosticStatus.NOT_TESTED }
    val totalCount: Int get() = items.size
}

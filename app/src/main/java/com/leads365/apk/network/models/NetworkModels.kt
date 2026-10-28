package com.leads365.apk.network.models

import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long = 3600L
)

@Serializable
data class TokenRefreshRequest(
    val refreshToken: String
)

@Serializable
data class AppVersionResponse(
    val minVersionCode: Int,
    val minVersionName: String,
    val isUpdateRequired: Boolean = false,
    val updateUrl: String? = null,
    val message: String = ""
)

@Serializable
data class ApiErrorResponse(
    val error: String? = null,
    val message: String? = null,
    val statusCode: Int? = null
)

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class HeartbeatRequest(
    val device_id: String? = null,
    val app_version: String? = null,
    val battery_optimization_ignored: Boolean? = null,
    val permissions_all_granted: Boolean? = null,
    val storage_access_granted: Boolean? = null,
    val auto_record_verified: Boolean? = null,
    val missing_recordings_count: Int = 0,
    val pending_uploads_count: Int = 0,
    val last_sync_epoch_ms: Long? = null,
    val last_successful_upload_epoch_ms: Long? = null,
    val health_state: String = "HEALTHY"
)

@Serializable
data class HeartbeatResponse(
    val success: Boolean,
    val message: String? = null,
    val device_status: String? = null,
    val revoked: Boolean = false,
    val timestamp: Long? = null
)

@Serializable
data class DiagnosticsReportRequest(
    val device_id: String? = null,
    val report_timestamp: Long = System.currentTimeMillis(),
    val diagnostics: Map<String, String> = emptyMap()
)

@Serializable
data class DiagnosticsReportResponse(
    val success: Boolean,
    val message: String? = null,
    val report_id: String? = null
)

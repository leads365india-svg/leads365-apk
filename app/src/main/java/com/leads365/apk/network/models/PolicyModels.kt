package com.leads365.apk.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PolicyData(
    @SerialName("agency_id") val agencyId: String = "default_agency",
    @SerialName("agency_name") val agencyName: String = "Leads365 Agency",
    @SerialName("require_consent_reminder") val requireConsentReminder: Boolean = true,
    @SerialName("consent_notice_text") val consentNoticeText: String = "",
    @SerialName("allow_recording_pause") val allowRecordingPause: Boolean = true,
    @SerialName("recording_sync_paused") val recordingSyncPaused: Boolean = false,
    @SerialName("enforce_strict_dnr") val enforceStrictDnr: Boolean = true,
    @SerialName("server_time") val serverTime: Long = System.currentTimeMillis()
)

@Serializable
data class PolicyResponse(
    val success: Boolean = true,
    val data: PolicyData? = null,
    val error: String? = null
)

@Serializable
data class PauseSyncRequest(
    val paused: Boolean
)

@Serializable
data class PauseSyncResponse(
    val success: Boolean = true,
    val message: String? = null,
    @SerialName("recording_sync_paused") val recordingSyncPaused: Boolean = false,
    val error: String? = null
)

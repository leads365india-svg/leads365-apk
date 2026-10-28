package com.leads365.apk.telephony

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 18: Cloud Telephony Request & Lifecycle Models
 */

@Serializable
data class InitiateCloudCallRequest(
    @SerialName("client_call_id") val clientCallId: String,
    @SerialName("lead_id") val leadId: String,
    @SerialName("lead_phone") val leadPhone: String,
    @SerialName("agent_phone") val agentPhone: String? = null,
    @SerialName("record_call") val recordCall: Boolean = true,
    @SerialName("provider") val provider: String = "auto"
)

@Serializable
data class CloudCallStatusResponse(
    val success: Boolean = true,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("provider_call_id") val providerCallId: String? = null,
    val status: String = "initiated", // "initiated", "connecting_agent", "ringing_lead", "in_progress", "completed", "failed"
    @SerialName("duration_seconds") val durationSeconds: Long = 0L,
    @SerialName("recording_status") val recordingStatus: String? = null, // "pending", "ready", "failed"
    @SerialName("recording_url") val recordingUrl: String? = null,
    val message: String? = null,
    val error: String? = null
)

sealed class CloudCallUiState {
    object Idle : CloudCallUiState()
    data class Initiating(val clientCallId: String, val leadName: String) : CloudCallUiState()
    data class RingingAgent(val callId: String, val leadName: String, val message: String) : CloudCallUiState()
    data class Connected(val callId: String, val leadName: String) : CloudCallUiState()
    data class Completed(
        val callId: String,
        val leadId: String,
        val durationSeconds: Long,
        val recordingStatus: String
    ) : CloudCallUiState()
    data class Error(val message: String, val isProviderUnconfigured: Boolean = false) : CloudCallUiState()
}

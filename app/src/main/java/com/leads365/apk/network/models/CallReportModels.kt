package com.leads365.apk.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CallReportRequest(
    @SerialName("client_call_id") val clientCallId: String,
    @SerialName("lead_id") val leadId: String,
    @SerialName("phone_number") val phoneNumber: String,
    @SerialName("call_direction") val callDirection: String,
    @SerialName("start_time_ms") val startTimeMs: Long,
    @SerialName("duration_seconds") val durationSeconds: Long,
    @SerialName("call_outcome") val callOutcome: String? = null,
    val notes: String? = null,
    @SerialName("next_followup_at") val nextFollowUpAt: Long? = null,
    @SerialName("sim_id") val simId: String? = null,
    @SerialName("auto_create_lead") val autoCreateLead: Boolean? = null,
    @SerialName("call_type") val callType: Int? = null
)

@Serializable
data class CallReportResponse(
    val success: Boolean = true,
    @SerialName("call_id") val callId: String? = null,
    @SerialName("lead_id") val leadId: String? = null,
    @SerialName("auto_lead_created") val autoLeadCreated: Boolean? = null,
    val message: String? = null,
    val error: String? = null
)

@Serializable
data class CallDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("clientCallId") val clientCallId: String? = null,
    @SerialName("leadId") val leadId: String? = null,
    @SerialName("direction") val direction: String? = null,
    @SerialName("callDirection") val callDirection: String? = null,
    @SerialName("number") val number: String? = null,
    @SerialName("phoneNumber") val phoneNumber: String? = null,
    @SerialName("startTimeMs") val startTimeMs: Long? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("durationSeconds") val durationSeconds: Long? = null,
    @SerialName("callOutcome") val callOutcome: String? = null,
    @SerialName("outcome") val outcome: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("simId") val simId: String? = null,
    @SerialName("hasRecording") val hasRecording: Boolean = false,
    @SerialName("recordingFileId") val recordingFileId: String? = null,
    @SerialName("isUnlinked") val isUnlinked: Boolean = false
)

@Serializable
data class CallListResponse(
    val success: Boolean = true,
    val data: List<CallDto> = emptyList(),
    val error: String? = null,
    val message: String? = null
)

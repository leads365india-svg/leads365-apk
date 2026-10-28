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
    @SerialName("sim_id") val simId: String? = null
)

@Serializable
data class CallReportResponse(
    val success: Boolean = true,
    @SerialName("call_id") val callId: String? = null,
    val message: String? = null,
    val error: String? = null
)

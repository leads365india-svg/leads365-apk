package com.leads365.apk.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LinkedLeadDto(
    @SerialName("_id") val mongoId: String? = null,
    val id: String? = null,
    val name: String = "",
    val phone: String = "",
    val status: String = "New"
)

@Serializable
data class FollowUpDto(
    @SerialName("_id") val mongoId: String? = null,
    val id: String? = null,
    val leadId: String? = null,
    val lead: LinkedLeadDto? = null,
    val title: String = "",
    val type: String = "call",
    val scheduledAt: String = "",
    val status: String = "pending",
    val priority: String = "medium",
    val notes: String = "",
    val assignedTo: String = "",
    val isOverdue: Boolean = false,
    val isToday: Boolean = false
)

@Serializable
data class FollowUpListData(
    val followUps: List<FollowUpDto> = emptyList(),
    val total: Int = 0
)

@Serializable
data class FollowUpListResponse(
    val success: Boolean = true,
    val followUps: List<FollowUpDto> = emptyList(),
    val data: FollowUpListData? = null,
    val error: String? = null
) {
    val items: List<FollowUpDto>
        get() = if (followUps.isNotEmpty()) followUps else data?.followUps.orEmpty()
}

@Serializable
data class CreateFollowUpRequest(
    val leadId: String,
    val title: String,
    val type: String = "call",
    val scheduledAt: String,
    val priority: String = "medium",
    val notes: String = ""
)

@Serializable
data class UpdateFollowUpRequest(
    val scheduledAt: String? = null,
    val notes: String? = null,
    val priority: String? = null
)

@Serializable
data class CompleteFollowUpRequest(
    val completionNotes: String = ""
)

@Serializable
data class FollowUpActionResponse(
    val success: Boolean = true,
    val data: FollowUpDto? = null,
    val message: String? = null,
    val error: String? = null
)

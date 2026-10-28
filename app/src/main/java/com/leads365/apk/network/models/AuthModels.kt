package com.leads365.apk.network.models

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val deviceId: String? = null
)

@Serializable
data class LoginResponse(
    val success: Boolean,
    val data: AuthData? = null,
    val message: String? = null,
    val error: String? = null,
    val errorCode: String? = null
)

@Serializable
data class AuthData(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSec: Long = 86400L,
    val user: UserDto,
    val agency: AgencyDto? = null,
    val minVersionCode: Int? = null,
    val isUpdateRequired: Boolean = false,
    val isRevoked: Boolean = false
)

@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val email: String,
    val role: String = "agent",
    val status: String = "active"
)

@Serializable
data class AgencyDto(
    val id: String,
    val name: String,
    val accountId: String? = null
)

@Serializable
data class DeviceRegisterRequest(
    val deviceModel: String,
    val manufacturer: String,
    val osVersion: String,
    val sdkInt: Int,
    val appVersionCode: Int,
    val appVersionName: String,
    val fcmToken: String? = null
)

@Serializable
data class DeviceRegisterResponse(
    val success: Boolean,
    val data: DeviceData? = null,
    val message: String? = null,
    val error: String? = null,
    val errorCode: String? = null
)

@Serializable
data class DeviceData(
    val deviceId: String,
    val status: String = "ACTIVE",
    val registeredAt: Long = System.currentTimeMillis()
)

@Serializable
data class PushTokenRequest(
    val token: String,
    val deviceId: String? = null
)

@Serializable
data class PushTokenResponse(
    val success: Boolean,
    val data: PushTokenData? = null,
    val message: String? = null,
    val error: String? = null,
    val errorCode: String? = null
)

@Serializable
data class PushTokenData(
    val deviceId: String,
    val fcmToken: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

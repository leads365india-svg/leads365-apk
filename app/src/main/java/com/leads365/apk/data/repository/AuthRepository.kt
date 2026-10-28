package com.leads365.apk.data.repository

import com.leads365.apk.core.AppResult
import com.leads365.apk.network.models.AgencyDto
import com.leads365.apk.network.models.AuthData
import com.leads365.apk.network.models.DeviceData
import com.leads365.apk.network.models.UserDto
import kotlinx.coroutines.flow.StateFlow

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Authenticating : AuthState()
    data class Authenticated(
        val user: UserDto,
        val agency: AgencyDto?,
        val deviceId: String?
    ) : AuthState()
    data class SessionRevoked(val reason: String) : AuthState()
    data class UpdateRequired(val minVersionCode: Int, val message: String) : AuthState()
}

interface AuthRepository {
    val authState: StateFlow<AuthState>
    fun isLoggedIn(): Boolean
    fun getCurrentUser(): UserDto?
    fun getAgencyName(): String?
    fun getDeviceId(): String?

    suspend fun login(email: String, password: String): AppResult<AuthData>
    suspend fun registerDevice(
        deviceModel: String,
        manufacturer: String,
        osVersion: String,
        sdkInt: Int,
        appVersionCode: Int,
        appVersionName: String,
        fcmToken: String? = null
    ): AppResult<DeviceData>

    suspend fun updatePushToken(token: String): AppResult<Unit>
    fun getSavedFcmToken(): String?
    fun saveFcmToken(token: String)

    suspend fun logout(preservePendingUploads: Boolean = true): AppResult<Unit>
    fun invalidateSession(reason: String? = null)
}

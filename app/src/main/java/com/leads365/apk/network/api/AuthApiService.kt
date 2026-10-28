package com.leads365.apk.network.api

import com.leads365.apk.network.models.DeviceRegisterRequest
import com.leads365.apk.network.models.DeviceRegisterResponse
import com.leads365.apk.network.models.LoginRequest
import com.leads365.apk.network.models.LoginResponse
import com.leads365.apk.network.models.PushTokenRequest
import com.leads365.apk.network.models.PushTokenResponse
import com.leads365.apk.network.models.TokenRefreshRequest
import com.leads365.apk.network.models.TokenResponse
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface AuthApiService {

    @POST("mobile/v1/auth/login")
    @Headers("No-Authentication: true")
    suspend fun login(
        @Body request: LoginRequest
    ): LoginResponse

    @POST("mobile/v1/devices/register")
    suspend fun registerDevice(
        @Body request: DeviceRegisterRequest
    ): DeviceRegisterResponse

    @POST("mobile/v1/push-token")
    suspend fun updatePushToken(
        @Body request: PushTokenRequest
    ): PushTokenResponse

    @POST("mobile/v1/auth/refresh")
    @Headers("No-Authentication: true")
    suspend fun refreshToken(
        @Body request: TokenRefreshRequest
    ): TokenResponse

    @POST("mobile/v1/auth/logout")
    suspend fun logout(): LoginResponse

    @POST("mobile/v1/heartbeat")
    suspend fun sendHeartbeat(
        @Body request: com.leads365.apk.network.models.HeartbeatRequest
    ): com.leads365.apk.network.models.HeartbeatResponse

    @POST("mobile/v1/diagnostics")
    suspend fun sendDiagnostics(
        @Body request: com.leads365.apk.network.models.DiagnosticsReportRequest
    ): com.leads365.apk.network.models.DiagnosticsReportResponse
}

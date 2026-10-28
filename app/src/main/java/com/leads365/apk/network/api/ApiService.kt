package com.leads365.apk.network.api

import com.leads365.apk.network.models.AppVersionResponse
import com.leads365.apk.network.models.HealthResponse
import com.leads365.apk.network.models.TokenRefreshRequest
import com.leads365.apk.network.models.TokenResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST

interface ApiService {

    @GET("version/check")
    @Headers("No-Authentication: true")
    suspend fun checkAppVersion(): AppVersionResponse

    @POST("auth/refresh")
    @Headers("No-Authentication: true")
    suspend fun refreshToken(@Body request: TokenRefreshRequest): TokenResponse

    @GET("health")
    @Headers("No-Authentication: true")
    suspend fun checkHealth(): HealthResponse

    @GET("user/profile")
    suspend fun getProfile(): HealthResponse
}

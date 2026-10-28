package com.leads365.apk.network.auth

import com.leads365.apk.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

class AccessTokenInterceptor(
    private val tokenProvider: TokenProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Base builder with client app version headers for backend version enforcement
        val builder = original.newBuilder()
            .header(HEADER_APP_VERSION, BuildConfig.VERSION_NAME)
            .header(HEADER_APP_VERSION_CODE, BuildConfig.VERSION_CODE.toString())
            .header(HEADER_CLIENT_PLATFORM, "android")

        // Skip auth injection if explicitly opted out
        if (original.header(NO_AUTH_HEADER) != null) {
            val newRequest = builder
                .removeHeader(NO_AUTH_HEADER)
                .build()
            return chain.proceed(newRequest)
        }

        val token = tokenProvider.getAccessToken()
        if (!token.isNullOrBlank()) {
            builder.header(HEADER_AUTHORIZATION, "Bearer $token")
        }

        return chain.proceed(builder.build())
    }

    companion object {
        const val NO_AUTH_HEADER = "No-Authentication"
        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_APP_VERSION = "X-App-Version"
        const val HEADER_APP_VERSION_CODE = "X-App-Version-Code"
        const val HEADER_CLIENT_PLATFORM = "X-Client-Platform"
    }
}

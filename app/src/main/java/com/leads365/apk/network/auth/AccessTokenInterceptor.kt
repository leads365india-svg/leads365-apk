package com.leads365.apk.network.auth

import okhttp3.Interceptor
import okhttp3.Response

class AccessTokenInterceptor(
    private val tokenProvider: TokenProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Skip auth injection if explicitly opted out
        if (original.header(NO_AUTH_HEADER) != null) {
            val newRequest = original.newBuilder()
                .removeHeader(NO_AUTH_HEADER)
                .build()
            return chain.proceed(newRequest)
        }

        val token = tokenProvider.getAccessToken()
        val request = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .header(HEADER_AUTHORIZATION, "Bearer $token")
                .build()
        } else {
            original
        }

        return chain.proceed(request)
    }

    companion object {
        const val NO_AUTH_HEADER = "No-Authentication"
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}

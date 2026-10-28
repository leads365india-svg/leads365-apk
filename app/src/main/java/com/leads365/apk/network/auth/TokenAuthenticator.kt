package com.leads365.apk.network.auth

import com.leads365.apk.network.models.TokenResponse
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class TokenAuthenticator(
    private val tokenProvider: TokenProvider,
    private val tokenRefresher: (refreshToken: String) -> TokenResponse?,
    private val onAuthFailed: (() -> Unit)? = null
) : Authenticator {

    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Prevent infinite retry loops: maximum 1 retry for 401
        if (responseCount(response) >= 2) {
            tokenProvider.clearTokens()
            onAuthFailed?.invoke()
            return null
        }

        val failedRequestToken = response.request.header(HEADER_AUTHORIZATION)
            ?.removePrefix("Bearer ")
            ?.trim()

        synchronized(refreshLock) {
            val currentToken = tokenProvider.getAccessToken()

            // If another thread already successfully refreshed the token, reuse it directly
            if (!currentToken.isNullOrBlank() && currentToken != failedRequestToken) {
                return response.request.newBuilder()
                    .header(HEADER_AUTHORIZATION, "Bearer $currentToken")
                    .build()
            }

            val refreshToken = tokenProvider.getRefreshToken()
            if (refreshToken.isNullOrBlank()) {
                tokenProvider.clearTokens()
                onAuthFailed?.invoke()
                return null
            }

            val newTokens = try {
                tokenRefresher(refreshToken)
            } catch (e: Exception) {
                null
            }

            return if (newTokens != null && newTokens.accessToken.isNotBlank()) {
                tokenProvider.saveTokens(newTokens.accessToken, newTokens.refreshToken)
                response.request.newBuilder()
                    .header(HEADER_AUTHORIZATION, "Bearer ${newTokens.accessToken}")
                    .build()
            } else {
                // Refresh failed or revoked by server
                tokenProvider.clearTokens()
                onAuthFailed?.invoke()
                null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        private const val HEADER_AUTHORIZATION = "Authorization"
    }
}

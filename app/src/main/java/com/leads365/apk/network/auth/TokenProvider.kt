package com.leads365.apk.network.auth

import android.content.Context
import android.content.SharedPreferences

interface TokenProvider {
    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun saveTokens(accessToken: String, refreshToken: String)
    fun clearTokens()
    fun getDeviceIdOrInstallId(): String? = null
    fun getAgencyAccountId(): String? = null
}

class InMemoryTokenProvider(
    private var accessToken: String? = null,
    private var refreshToken: String? = null,
    private var deviceIdOrInstallId: String? = null,
    private var agencyAccountId: String? = null
) : TokenProvider {

    override fun getAccessToken(): String? = accessToken

    override fun getRefreshToken(): String? = refreshToken

    override fun saveTokens(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }

    override fun clearTokens() {
        this.accessToken = null
        this.refreshToken = null
    }

    override fun getDeviceIdOrInstallId(): String? = deviceIdOrInstallId

    override fun getAgencyAccountId(): String? = agencyAccountId

    fun setDeviceIdOrInstallId(id: String?) {
        this.deviceIdOrInstallId = id
    }

    fun setAgencyAccountId(id: String?) {
        this.agencyAccountId = id
    }
}

class SharedPreferencesTokenProvider(
    private val prefs: SharedPreferences
) : TokenProvider {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    override fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    override fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    override fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    override fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    override fun getDeviceIdOrInstallId(): String? = prefs.getString(KEY_INSTALL_ID, null)

    override fun getAgencyAccountId(): String? = prefs.getString(KEY_AGENCY_ACCOUNT_ID, null)

    companion object {
        private const val PREFS_NAME = "leads365_auth_tokens"
        private const val KEY_ACCESS_TOKEN = "key_access_token"
        private const val KEY_REFRESH_TOKEN = "key_refresh_token"
        private const val KEY_INSTALL_ID = "key_install_id"
        private const val KEY_AGENCY_ACCOUNT_ID = "key_agency_account_id"
    }
}

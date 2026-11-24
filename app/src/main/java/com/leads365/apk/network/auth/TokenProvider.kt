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
    fun getActiveWorkspaceId(): String? = null
    fun saveActiveWorkspaceId(workspaceId: String?) {}
    fun getWorkspaces(): List<com.leads365.apk.network.models.WorkspaceDto>? = null
    fun saveWorkspaces(workspaces: List<com.leads365.apk.network.models.WorkspaceDto>?) {}
}

class InMemoryTokenProvider(
    private var accessToken: String? = null,
    private var refreshToken: String? = null,
    private var deviceIdOrInstallId: String? = null,
    private var agencyAccountId: String? = null,
    private var activeWorkspaceId: String? = null,
    private var workspaces: List<com.leads365.apk.network.models.WorkspaceDto>? = null
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
        this.activeWorkspaceId = null
        this.workspaces = null
    }

    override fun getDeviceIdOrInstallId(): String? = deviceIdOrInstallId

    override fun getAgencyAccountId(): String? = agencyAccountId

    override fun getActiveWorkspaceId(): String? = activeWorkspaceId

    override fun saveActiveWorkspaceId(workspaceId: String?) {
        this.activeWorkspaceId = workspaceId
    }

    override fun getWorkspaces(): List<com.leads365.apk.network.models.WorkspaceDto>? = workspaces

    override fun saveWorkspaces(workspaces: List<com.leads365.apk.network.models.WorkspaceDto>?) {
        this.workspaces = workspaces
    }

    fun setDeviceIdOrInstallId(id: String?) {
        this.deviceIdOrInstallId = id
    }

    fun setAgencyAccountId(id: String?) {
        this.agencyAccountId = id
    }

    fun setActiveWorkspaceId(id: String?) {
        this.activeWorkspaceId = id
    }

    fun setWorkspaces(workspaces: List<com.leads365.apk.network.models.WorkspaceDto>?) {
        this.workspaces = workspaces
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
            .remove(KEY_ACTIVE_WORKSPACE_ID)
            .remove(KEY_WORKSPACES_JSON)
            .apply()
    }

    override fun getDeviceIdOrInstallId(): String? = prefs.getString(KEY_INSTALL_ID, null)

    override fun getAgencyAccountId(): String? = prefs.getString(KEY_AGENCY_ACCOUNT_ID, null)

    override fun getActiveWorkspaceId(): String? = prefs.getString(KEY_ACTIVE_WORKSPACE_ID, null)

    override fun saveActiveWorkspaceId(workspaceId: String?) {
        if (workspaceId != null) {
            prefs.edit().putString(KEY_ACTIVE_WORKSPACE_ID, workspaceId).apply()
        } else {
            prefs.edit().remove(KEY_ACTIVE_WORKSPACE_ID).apply()
        }
    }

    override fun getWorkspaces(): List<com.leads365.apk.network.models.WorkspaceDto>? {
        val raw = prefs.getString(KEY_WORKSPACES_JSON, null) ?: return null
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<com.leads365.apk.network.models.WorkspaceDto>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    com.leads365.apk.network.models.WorkspaceDto(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        slug = if (obj.has("slug") && !obj.isNull("slug")) obj.getString("slug") else null,
                        isDefault = obj.optBoolean("isDefault", false),
                        role = if (obj.has("role") && !obj.isNull("role")) obj.getString("role") else null
                    )
                )
            }
            list
        } catch (e: Exception) {
            null
        }
    }

    override fun saveWorkspaces(workspaces: List<com.leads365.apk.network.models.WorkspaceDto>?) {
        if (workspaces != null) {
            val array = org.json.JSONArray()
            workspaces.forEach { ws ->
                val obj = org.json.JSONObject()
                obj.put("id", ws.id)
                obj.put("name", ws.name)
                obj.put("slug", ws.slug)
                obj.put("isDefault", ws.isDefault)
                obj.put("role", ws.role)
                array.put(obj)
            }
            prefs.edit().putString(KEY_WORKSPACES_JSON, array.toString()).apply()
        } else {
            prefs.edit().remove(KEY_WORKSPACES_JSON).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "leads365_auth_tokens"
        private const val KEY_ACCESS_TOKEN = "key_access_token"
        private const val KEY_REFRESH_TOKEN = "key_refresh_token"
        private const val KEY_INSTALL_ID = "key_install_id"
        private const val KEY_AGENCY_ACCOUNT_ID = "key_agency_account_id"
        private const val KEY_ACTIVE_WORKSPACE_ID = "key_active_workspace_id"
        private const val KEY_WORKSPACES_JSON = "key_workspaces_json"
    }
}

package com.leads365.apk.calls

import android.content.Context
import android.content.SharedPreferences
import com.leads365.apk.network.auth.TokenProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration and preference store for automatic lead creation from answered incoming calls.
 *
 * Requirements:
 * - Disabled by default (additive feature; existing behavior strictly preserved when OFF).
 * - Configurable minimum duration threshold (default: 10 seconds).
 * - Multi-tenant isolation: preferences are strictly partitioned per agency account / tenant.
 *   Enabling the setting for Tenant A never enables it for Tenant B on the same device.
 */
@Singleton
class AutoLeadCaptureConfig(
    @ApplicationContext private val context: Context,
    private val tokenProvider: TokenProvider?,
    @Suppress("UNUSED_PARAMETER") dummy: Unit = Unit
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        tokenProvider: TokenProvider
    ) : this(context, tokenProvider, Unit)

    constructor(
        @ApplicationContext context: Context
    ) : this(context, null, Unit)

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Resolves preference key scoped to the active tenant.
     * Prevents cross-tenant configuration bleeding when devices switch accounts.
     */
    fun getScopedKey(baseKey: String, tenantId: String? = null): String {
        val resolvedTenant = tenantId?.trim()?.ifEmpty { null }
            ?: tokenProvider?.getAgencyAccountId()?.trim()?.ifEmpty { null }
        return if (resolvedTenant != null) {
            "${baseKey}_${resolvedTenant}"
        } else {
            baseKey
        }
    }

    /**
     * Whether automatic lead creation from answered incoming calls is enabled.
     * Default: false (must be explicitly enabled by user/admin).
     */
    fun isAutoLeadCreationEnabled(tenantId: String? = null): Boolean {
        val key = getScopedKey(KEY_AUTO_CREATE_LEAD, tenantId)
        return prefs.getBoolean(key, DEFAULT_ENABLED)
    }

    /**
     * Update the auto lead creation toggle state for the target tenant.
     */
    fun setAutoLeadCreationEnabled(enabled: Boolean, tenantId: String? = null) {
        val key = getScopedKey(KEY_AUTO_CREATE_LEAD, tenantId)
        prefs.edit().putBoolean(key, enabled).apply()
    }

    /**
     * Minimum duration in seconds required for an answered incoming call to trigger lead creation.
     * Default: 10 seconds.
     */
    fun getMinDurationSeconds(tenantId: String? = null): Int {
        val key = getScopedKey(KEY_MIN_DURATION_SECONDS, tenantId)
        return prefs.getInt(key, DEFAULT_MIN_DURATION_SECONDS)
    }

    /**
     * Update the minimum call duration threshold for the target tenant.
     */
    fun setMinDurationSeconds(seconds: Int, tenantId: String? = null) {
        val key = getScopedKey(KEY_MIN_DURATION_SECONDS, tenantId)
        prefs.edit().putInt(key, seconds.coerceAtLeast(1)).apply()
    }

    companion object {
        const val PREFS_NAME = "com.leads365.apk.preferences"
        const val KEY_AUTO_CREATE_LEAD = "key_auto_create_lead_from_incoming_calls"
        const val KEY_MIN_DURATION_SECONDS = "key_auto_create_lead_min_duration_seconds"

        const val DEFAULT_ENABLED = false
        const val DEFAULT_MIN_DURATION_SECONDS = 10
    }
}

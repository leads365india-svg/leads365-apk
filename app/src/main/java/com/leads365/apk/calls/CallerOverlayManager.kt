package com.leads365.apk.calls

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class CallerOverlayManager @Inject constructor() {

    /**
     * System overlay drawing (SYSTEM_ALERT_WINDOW) is intentionally disabled;
     * caller identification uses standard high-priority heads-up notifications.
     */
    open fun canDrawOverlays(context: Context): Boolean = false

    open fun showCallerOverlay(
        context: Context,
        leadId: String,
        leadName: String,
        leadStatus: String,
        lastNote: String?,
        normalizedNumber: String
    ): Boolean = false

    open fun dismissOverlay() {
        // No floating window attached when using heads-up notifications.
    }

    companion object {
        const val TAG = "CallerOverlayManager"
    }
}

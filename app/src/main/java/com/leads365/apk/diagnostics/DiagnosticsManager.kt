package com.leads365.apk.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.leads365.apk.core.CompatibilityStatus
import com.leads365.apk.core.DiagnosticsReport
import com.leads365.apk.core.PermissionItem
import com.leads365.apk.core.PermissionStatus
import com.leads365.apk.core.PrivacyRedactor
import org.json.JSONObject

class DiagnosticsManager(
    private val context: Context,
    private val isSafConfigured: () -> Boolean,
    private val isSafValid: () -> Boolean,
    private val isMediaStoreAccessible: () -> Boolean,
    private val diagnoseCallLog: () -> com.leads365.apk.calls.CallLogDiagnosticResult = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) com.leads365.apk.calls.CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS
        else com.leads365.apk.calls.CallLogDiagnosticResult.PERMISSION_NOT_GRANTED
    }
) {

    fun getDeviceModel(): String = Build.MODEL
    fun getManufacturer(): String = Build.MANUFACTURER
    fun getAndroidVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    fun getSdkInt(): Int = Build.VERSION.SDK_INT

    fun isDialerSandboxed(): Boolean {
        val mfg = getManufacturer().lowercase()
        val model = getDeviceModel().lowercase()
        return mfg.contains("google") || model.contains("pixel")
    }

    fun checkPermissions(): List<PermissionItem> {
        val items = mutableListOf<PermissionItem>()

        val phoneStateGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        items.add(
            PermissionItem(
                id = "perm_phone_state",
                title = "Phone State Access",
                rationale = "Enables detection of call completion without reading microphone or audio streams.",
                status = if (phoneStateGranted) PermissionStatus.GRANTED else PermissionStatus.NOT_GRANTED,
                isMandatory = true
            )
        )

        val callLogDiag = diagnoseCallLog()
        val (callLogStatusEnum, callLogRationale) = when (callLogDiag) {
            com.leads365.apk.calls.CallLogDiagnosticResult.PERMISSION_GRANTED_QUERY_SUCCESS ->
                Pair(PermissionStatus.GRANTED, "Call log access verified and provider query succeeded.")
            com.leads365.apk.calls.CallLogDiagnosticResult.QUERY_EMPTY ->
                Pair(PermissionStatus.GRANTED, "Call log access verified (history is empty, 0 records). Ready for test call.")
            com.leads365.apk.calls.CallLogDiagnosticResult.PERMISSION_NOT_GRANTED ->
                Pair(PermissionStatus.NOT_GRANTED, "Call Log permission has not been granted. Grant permission to verify calls against the lead allowlist.")
            com.leads365.apk.calls.CallLogDiagnosticResult.PERMISSION_RESTRICTED ->
                Pair(PermissionStatus.RESTRICTED, "Access is restricted by Android system or Vivo privacy settings. Check App Permissions in Android Settings.")
            com.leads365.apk.calls.CallLogDiagnosticResult.PROVIDER_UNAVAILABLE ->
                Pair(PermissionStatus.UNAVAILABLE, "Call log content provider returned no data or is temporarily unavailable.")
            com.leads365.apk.calls.CallLogDiagnosticResult.QUERY_FAILED ->
                Pair(PermissionStatus.ERROR, "Call log provider query failed. Please check system logs or retry.")
        }
        items.add(
            PermissionItem(
                id = "perm_call_log",
                title = "Call Log Access",
                rationale = callLogRationale,
                status = callLogStatusEnum,
                isMandatory = true
            )
        )

        // Storage / Audio permission
        val audioGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        items.add(
            PermissionItem(
                id = "perm_audio_access",
                title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Media Audio Access" else "Storage Access",
                rationale = "Allows MediaStore discovery method to query dialer call recordings in the bounded time window.",
                status = if (audioGranted) PermissionStatus.GRANTED else PermissionStatus.NOT_GRANTED,
                isMandatory = false
            )
        )

        val safConfigured = isSafConfigured()
        val safValid = isSafValid()
        items.add(
            PermissionItem(
                id = "perm_saf_folder",
                title = "Storage Access Framework (SAF) Folder",
                rationale = when {
                    safConfigured && safValid -> "Persisted folder permission active. Folder access is granted."
                    safConfigured && !safValid -> "Previously selected folder access has been revoked or lost. Re-selection required."
                    else -> "User-selected folder permission where phone dialer saves audio recordings."
                },
                status = when {
                    safConfigured && safValid -> PermissionStatus.GRANTED
                    safConfigured && !safValid -> PermissionStatus.RESTRICTED
                    else -> PermissionStatus.NOT_REQUESTED
                },
                isMandatory = false
            )
        )

        // Optional direct calling permission (with dialer fallback)
        val callPhoneGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        items.add(
            PermissionItem(
                id = "perm_call_phone",
                title = "Direct Calling (Optional)",
                rationale = "Allows one-tap direct calling from the app. If not granted, calls will open in your default phone dialer.",
                status = if (callPhoneGranted) PermissionStatus.GRANTED else PermissionStatus.NOT_GRANTED,
                isMandatory = false
            )
        )

        // Post Notifications permission (API 33+; granted automatically on older versions)
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        items.add(
            PermissionItem(
                id = "perm_notifications",
                title = "Post Notifications",
                rationale = "Alerts the user when a test call is matched or when background observation completes.",
                status = if (notifGranted) PermissionStatus.GRANTED else PermissionStatus.NOT_GRANTED,
                isMandatory = false
            )
        )

        // Optional overlay permission for caller pop-ups
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.provider.Settings.canDrawOverlays(context)
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }
        items.add(
            PermissionItem(
                id = "perm_overlay",
                title = "Caller Pop-up Window (Optional)",
                rationale = "Allows display of lead identification badges over incoming or outgoing calls.",
                status = if (overlayGranted) PermissionStatus.GRANTED else PermissionStatus.NOT_GRANTED,
                isMandatory = false
            )
        )

        // Battery optimization exemption
        val powerManager = try {
            context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        } catch (e: Exception) {
            null
        }
        val batteryOptIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
            try {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }
        items.add(
            PermissionItem(
                id = "perm_battery_opt",
                title = "Background Execution / Battery (Optional)",
                rationale = "Prevents Android from stopping background call observation and delayed recording discovery.",
                status = if (batteryOptIgnored) PermissionStatus.GRANTED else PermissionStatus.NOT_GRANTED,
                isMandatory = false
            )
        )

        return items
    }

    fun computeCompatibilityStatus(
        hasTestCallRun: Boolean,
        hasSuccessfulMatch: Boolean,
        hasUploadVerified: Boolean
    ): CompatibilityStatus {
        val permissions = checkPermissions()
        val mandatoryMissing = permissions.filter { it.isMandatory && it.status != PermissionStatus.GRANTED }

        if (mandatoryMissing.isNotEmpty()) {
            return CompatibilityStatus.NEEDS_ATTENTION
        }

        // Neither SAF nor MediaStore accessible
        val safReady = isSafConfigured() && isSafValid()
        val mediaStoreReady = isMediaStoreAccessible()
        if (!safReady && !mediaStoreReady) {
            return CompatibilityStatus.NEEDS_ATTENTION
        }

        if (!hasTestCallRun) {
            return CompatibilityStatus.NOT_TESTED
        }

        return if (hasSuccessfulMatch && hasUploadVerified) {
            CompatibilityStatus.VERIFIED
        } else if (hasSuccessfulMatch) {
            CompatibilityStatus.PARTIALLY_VERIFIED
        } else {
            CompatibilityStatus.NEEDS_ATTENTION
        }
    }

    fun generateRedactedDiagnosticJson(
        report: DiagnosticsReport,
        auditLog: List<String>
    ): String {
        val json = JSONObject()
        json.put("report_generated_epoch_ms", report.reportTimestampEpochMs)
        json.put("device_model", report.deviceModel)
        json.put("manufacturer", report.manufacturer)
        json.put("android_version", report.androidVersion)
        json.put("sdk_int", report.sdkInt)

        val perms = JSONObject()
        perms.put("phone_state_granted", report.phoneStatePermissionGranted)
        perms.put("call_log_granted", report.callLogPermissionGranted)
        perms.put("saf_configured", report.safConfigured)
        perms.put("saf_tree_valid", report.safTreeUriValid)
        perms.put("media_store_accessible", report.mediaStoreAudioAccessible)
        json.put("permissions_status", perms)

        json.put("configured_test_leads_count", report.totalTestLeadsConfigured)
        json.put("last_gate_result", report.lastGateResult ?: "NONE")
        json.put("last_matching_status", report.lastMatchingStatus ?: "NONE")
        json.put("last_discovery_delay_sec", report.lastDiscoveryDelaySec ?: -1)
        json.put("overall_compatibility", report.overallCompatibility.name)

        val auditArray = org.json.JSONArray()
        auditLog.takeLast(50).forEach { line ->
            // Ensure phone numbers and file names are redacted in audit trace
            val redacted = PrivacyRedactor.redactFilename(
                PrivacyRedactor.redactPhoneNumber(line)
            )
            auditArray.put(redacted)
        }
        json.put("redacted_audit_trace", auditArray)

        return json.toString(2)
    }
}

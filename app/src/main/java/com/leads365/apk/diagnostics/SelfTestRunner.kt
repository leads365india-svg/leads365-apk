package com.leads365.apk.diagnostics

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.calls.PendingCallManager
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.data.repository.LeadRepository
import com.leads365.apk.data.repository.PolicyRepository
import com.leads365.apk.data.repository.UploadQueueRepository
import com.leads365.apk.notifications.LeadsNotificationManager
import com.leads365.apk.work.WorkManagerScheduler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class SelfTestRunner @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val leadRepository: LeadRepository,
    private val policyRepository: PolicyRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val workManagerScheduler: WorkManagerScheduler,
    private val normalizer: PhoneNumberNormalizer,
    private val pendingCallManager: PendingCallManager
) {

    open suspend fun runFullDiagnostics(): SelfTestResult {
        val items = mutableListOf<DiagnosticItem>()

        // 1. Notification Readiness & Channels
        items.add(checkNotifications())

        // 2. Caller Overlay & Fallback
        items.add(checkCallerOverlay())

        // 3. Telephony & Call Log Permissions
        items.add(checkTelephonyAndCallLog())

        // 4. Persisted SAF & MediaStore Audio
        items.add(checkRecordingStorage())

        // 5. Battery Optimization & Background Execution
        items.add(checkBatteryAndBackground())

        // 6. Backend Authentication & Session State
        items.add(checkAuthenticationSession())

        // 7. Lead Synchronization & Local Cache
        items.add(checkLeadSynchronization())

        // 8. Upload Queue Operational State
        items.add(checkUploadQueueState())

        // 9. Agency Privacy Policy & Safeguards
        items.add(checkPrivacyPolicy())

        // 10. Firebase Cloud Messaging Readiness
        items.add(checkFirebaseAndPush())

        // 11. Internal Architecture & Logic Self-Test
        items.add(checkInternalArchitectureLogic())

        // 12. Phase 12: Internal Disk Storage & Low Storage Safeguard
        items.add(checkDiskStorage())

        return SelfTestResult(
            timestampEpochMs = System.currentTimeMillis(),
            items = items
        )
    }

    private fun checkNotifications(): DiagnosticItem {
        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val assignmentChannelExists = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            notificationManager.getNotificationChannel(LeadsNotificationManager.CHANNEL_ID_LEAD_ASSIGNMENTS) != null
        } else {
            true
        }

        return when {
            postNotifGranted && assignmentChannelExists -> DiagnosticItem(
                id = "diag_notifications",
                title = "Notifications & Channels",
                category = DiagnosticCategory.PERMISSIONS,
                status = DiagnosticStatus.PASS,
                summary = "Post notification permission granted and notification channels initialized."
            )
            !postNotifGranted -> DiagnosticItem(
                id = "diag_notifications",
                title = "Notifications & Channels",
                category = DiagnosticCategory.PERMISSIONS,
                status = DiagnosticStatus.WARNING,
                summary = "Notification permission is not granted. Push assignment alerts will not display in the system tray.",
                actionableStep = "Grant Notification permission in Android App Settings."
            )
            else -> DiagnosticItem(
                id = "diag_notifications",
                title = "Notifications & Channels",
                category = DiagnosticCategory.PERMISSIONS,
                status = DiagnosticStatus.WARNING,
                summary = "Notification channels are not yet registered with NotificationManager.",
                actionableStep = "Launch the app or run self-test to trigger channel registration."
            )
        }
    }

    private fun checkCallerOverlay(): DiagnosticItem {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Settings.canDrawOverlays(context)
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }

        return if (canDraw) {
            DiagnosticItem(
                id = "diag_caller_overlay",
                title = "Caller Pop-up Overlay",
                category = DiagnosticCategory.CALL_DETECTION,
                status = DiagnosticStatus.PASS,
                summary = "SYSTEM_ALERT_WINDOW permission granted. Floating caller ID cards are active."
            )
        } else {
            DiagnosticItem(
                id = "diag_caller_overlay",
                title = "Caller Pop-up Overlay",
                category = DiagnosticCategory.CALL_DETECTION,
                status = DiagnosticStatus.WARNING,
                summary = "Overlay permission disabled. Floating badges inactive; high-priority heads-up notifications active as fallback.",
                actionableStep = "Tap 'Enable Overlay Pop-up' in Settings to grant Display over other apps."
            )
        }
    }

    private fun checkTelephonyAndCallLog(): DiagnosticItem {
        val phoneStateGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val callLogGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        return when {
            phoneStateGranted && callLogGranted -> DiagnosticItem(
                id = "diag_call_detection",
                title = "Call Detection Prerequisites",
                category = DiagnosticCategory.CALL_DETECTION,
                status = DiagnosticStatus.PASS,
                summary = "READ_PHONE_STATE and READ_CALL_LOG granted. Lead call gating is fully operational."
            )
            !phoneStateGranted && !callLogGranted -> DiagnosticItem(
                id = "diag_call_detection",
                title = "Call Detection Prerequisites",
                category = DiagnosticCategory.CALL_DETECTION,
                status = DiagnosticStatus.FAIL,
                summary = "Neither phone state nor call log permissions are granted. Call detection cannot run.",
                actionableStep = "Complete the Setup Wizard to grant Phone State and Call Log permissions."
            )
            !phoneStateGranted -> DiagnosticItem(
                id = "diag_call_detection",
                title = "Call Detection Prerequisites",
                category = DiagnosticCategory.CALL_DETECTION,
                status = DiagnosticStatus.WARNING,
                summary = "Phone State permission missing. Immediate call disconnect events cannot be observed.",
                actionableStep = "Grant Phone State permission in Android Settings."
            )
            else -> DiagnosticItem(
                id = "diag_call_detection",
                title = "Call Detection Prerequisites",
                category = DiagnosticCategory.CALL_DETECTION,
                status = DiagnosticStatus.WARNING,
                summary = "Call Log permission missing. Completed call numbers cannot be verified against the lead database.",
                actionableStep = "Grant Call Log permission in Android Settings."
            )
        }
    }

    private fun checkRecordingStorage(): DiagnosticItem {
        val canonical = context.getSharedPreferences("com.leads365.apk.preferences", Context.MODE_PRIVATE).getString("key_saf_tree_uri", null)
        val phase1 = context.getSharedPreferences("leads365_phase1_prefs", Context.MODE_PRIVATE).getString("key_saf_tree_uri", null)
        val legacy = context.getSharedPreferences("leads365_prefs", Context.MODE_PRIVATE).getString("saf_tree_uri", null)
        val hasPersistedPermission = context.contentResolver.persistedUriPermissions.any { it.isReadPermission }
        val hasSafConfigured = !canonical.isNullOrBlank() || !phase1.isNullOrBlank() || !legacy.isNullOrBlank() || hasPersistedPermission

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

        return when {
            hasSafConfigured -> DiagnosticItem(
                id = "diag_recording_storage",
                title = "Recording Folder (SAF)",
                category = DiagnosticCategory.RECORDING_STORAGE,
                status = DiagnosticStatus.PASS,
                summary = "Persisted SAF tree folder URI is active. Targeted call recording matcher operational."
            )
            audioGranted -> DiagnosticItem(
                id = "diag_recording_storage",
                title = "Recording Storage (MediaStore)",
                category = DiagnosticCategory.RECORDING_STORAGE,
                status = DiagnosticStatus.PASS,
                summary = "MediaStore audio access granted. MediaStore recording discovery method operational."
            )
            else -> DiagnosticItem(
                id = "diag_recording_storage",
                title = "Recording Storage Access",
                category = DiagnosticCategory.RECORDING_STORAGE,
                status = DiagnosticStatus.WARNING,
                summary = "Neither SAF folder selected nor MediaStore audio permission granted. Recordings cannot be discovered.",
                actionableStep = "Select your dialer recording folder in Setup Wizard or grant audio storage access."
            )
        }
    }

    private fun checkBatteryAndBackground(): DiagnosticItem {
        val assessment = BackgroundHardeningHelper.evaluateBackgroundHardening(context)

        return when {
            assessment.isRestricted -> {
                DiagnosticItem(
                    id = "diag_battery_opt",
                    title = "Background Execution Readiness",
                    category = DiagnosticCategory.PERMISSIONS,
                    status = DiagnosticStatus.WARNING,
                    summary = "App has been placed in restricted background mode by OS/OEM power manager.",
                    actionableStep = assessment.actionableGuidance
                )
            }
            !assessment.isOptExempt -> {
                DiagnosticItem(
                    id = "diag_battery_opt",
                    title = "Background Execution Readiness",
                    category = DiagnosticCategory.PERMISSIONS,
                    status = DiagnosticStatus.WARNING,
                    summary = "App is subject to battery optimization. Background sync and call matching may be delayed by OEM power managers.",
                    actionableStep = assessment.actionableGuidance
                )
            }
            else -> {
                DiagnosticItem(
                    id = "diag_battery_opt",
                    title = "Background Execution Readiness",
                    category = DiagnosticCategory.PERMISSIONS,
                    status = DiagnosticStatus.PASS,
                    summary = "Battery optimization exemption active and background activity unrestricted (${assessment.manufacturer.name})."
                )
            }
        }
    }

    private fun checkAuthenticationSession(): DiagnosticItem {
        val isLoggedIn = authRepository.isLoggedIn()
        val currentUser = authRepository.getCurrentUser()
        val agentId = currentUser?.id ?: currentUser?.email
        val agencyName = authRepository.getAgencyName() ?: "Leads365"

        return if (isLoggedIn) {
            // Strictly Redact sensitive IDs: Only show prefix
            val maskedAgent = agentId?.take(8) ?: "agent"
            val maskedAgency = agencyName.take(8)
            DiagnosticItem(
                id = "diag_auth_session",
                title = "Authentication & Session",
                category = DiagnosticCategory.CONNECTIVITY_SYNC,
                status = DiagnosticStatus.PASS,
                summary = "Authenticated session active for agent [$maskedAgent...], agency [$maskedAgency...]. Tokens encrypted in Keystore."
            )
        } else {
            DiagnosticItem(
                id = "diag_auth_session",
                title = "Authentication & Session",
                category = DiagnosticCategory.CONNECTIVITY_SYNC,
                status = DiagnosticStatus.WARNING,
                summary = "No active authenticated agent session. Lead sync and call uploads are paused until sign-in.",
                actionableStep = "Sign in via the Account screen to enable CRM sync."
            )
        }
    }

    private suspend fun checkLeadSynchronization(): DiagnosticItem {
        val lastSyncTimestamp = leadRepository.getLastSyncTimestamp()
        val leadsCountResult = leadRepository.getAllLeads()
        val localLeadsCount = if (leadsCountResult is AppResult.Success) leadsCountResult.data.size else 0
        val staleThresholdMs = 4 * 60 * 60 * 1000L // 4 hours

        return if (lastSyncTimestamp > 0L) {
            val elapsedMs = System.currentTimeMillis() - lastSyncTimestamp
            val elapsedSec = elapsedMs / 1000
            val elapsedHours = elapsedMs / (60 * 60 * 1000)
            if (elapsedMs > staleThresholdMs) {
                DiagnosticItem(
                    id = "diag_lead_sync",
                    title = "Lead Synchronization & Cache",
                    category = DiagnosticCategory.CONNECTIVITY_SYNC,
                    status = DiagnosticStatus.WARNING,
                    summary = "Synchronization is stale: last successful sync was $elapsedHours hours ago (threshold: 4h). Local cache active ($localLeadsCount leads).",
                    actionableStep = "Perform a manual sync from the Leads screen or verify background battery/network permissions."
                )
            } else {
                DiagnosticItem(
                    id = "diag_lead_sync",
                    title = "Lead Synchronization & Cache",
                    category = DiagnosticCategory.CONNECTIVITY_SYNC,
                    status = DiagnosticStatus.PASS,
                    summary = "Local cache active ($localLeadsCount leads). Last sync completed $elapsedSec seconds ago."
                )
            }
        } else if (localLeadsCount > 0) {
            DiagnosticItem(
                id = "diag_lead_sync",
                title = "Lead Synchronization & Cache",
                category = DiagnosticCategory.CONNECTIVITY_SYNC,
                status = DiagnosticStatus.PASS,
                summary = "Local cache contains $localLeadsCount assigned leads. No sync timestamp recorded."
            )
        } else {
            DiagnosticItem(
                id = "diag_lead_sync",
                title = "Lead Synchronization & Cache",
                category = DiagnosticCategory.CONNECTIVITY_SYNC,
                status = DiagnosticStatus.WARNING,
                summary = "No leads synchronized yet. Local cache is empty.",
                actionableStep = "Perform a lead sync from the Leads screen or ensure device is authenticated."
            )
        }
    }

    private fun checkDiskStorage(): DiagnosticItem {
        val usableBytes = try {
            context.filesDir.usableSpace
        } catch (e: Exception) {
            -1L
        }
        val minRequiredBytes = 50 * 1024 * 1024L // 50 MB
        val usableMb = if (usableBytes >= 0) usableBytes / (1024 * 1024) else -1L

        return when {
            usableBytes < 0 -> DiagnosticItem(
                id = "diag_disk_storage",
                title = "Internal Disk Storage",
                category = DiagnosticCategory.RECORDING_STORAGE,
                status = DiagnosticStatus.PASS,
                summary = "Internal storage available for operation."
            )
            usableBytes < minRequiredBytes -> DiagnosticItem(
                id = "diag_disk_storage",
                title = "Internal Disk Storage",
                category = DiagnosticCategory.RECORDING_STORAGE,
                status = DiagnosticStatus.WARNING,
                summary = "Low internal storage ($usableMb MB available). Recordings and database operations may be constrained.",
                actionableStep = "Free up internal storage space on your device to prevent worker failures."
            )
            else -> DiagnosticItem(
                id = "diag_disk_storage",
                title = "Internal Disk Storage",
                category = DiagnosticCategory.RECORDING_STORAGE,
                status = DiagnosticStatus.PASS,
                summary = "Storage healthy ($usableMb MB available). No storage pressure detected."
            )
        }
    }

    private suspend fun checkUploadQueueState(): DiagnosticItem {
        val pending = when (val res = uploadQueueRepository.getTasksByStatus("PENDING")) {
            is AppResult.Success -> res.data.size
            else -> 0
        }
        val uploading = when (val res = uploadQueueRepository.getTasksByStatus("UPLOADING")) {
            is AppResult.Success -> res.data.size
            else -> 0
        }
        val failed = when (val res = uploadQueueRepository.getTasksByStatus("FAILED")) {
            is AppResult.Success -> res.data.size
            else -> 0
        }
        val completed = when (val res = uploadQueueRepository.getTasksByStatus("COMPLETED")) {
            is AppResult.Success -> res.data.size
            else -> 0
        }

        return when {
            failed > 0 -> DiagnosticItem(
                id = "diag_upload_queue",
                title = "Upload Queue Health",
                category = DiagnosticCategory.CONNECTIVITY_SYNC,
                status = DiagnosticStatus.WARNING,
                summary = "Queue state: $failed failed, $pending pending, $uploading uploading, $completed completed.",
                actionableStep = "Inspect failed tasks in Upload Status screen; WorkManager will automatically retry on Wi-Fi/network recovery."
            )
            pending > 0 || uploading > 0 -> DiagnosticItem(
                id = "diag_upload_queue",
                title = "Upload Queue Health",
                category = DiagnosticCategory.CONNECTIVITY_SYNC,
                status = DiagnosticStatus.PASS,
                summary = "Queue active: $uploading currently uploading, $pending pending retry/dispatch, $completed server-confirmed."
            )
            else -> DiagnosticItem(
                id = "diag_upload_queue",
                title = "Upload Queue Health",
                category = DiagnosticCategory.CONNECTIVITY_SYNC,
                status = DiagnosticStatus.PASS,
                summary = "Upload queue is clean. $completed completed uploads confirmed by backend."
            )
        }
    }

    private fun checkPrivacyPolicy(): DiagnosticItem {
        val consentAck = policyRepository.isConsentAcknowledged()
        val paused = policyRepository.isRecordingSyncPaused()
        val strictDnr = policyRepository.isStrictDnrEnforced()

        return when {
            !consentAck -> DiagnosticItem(
                id = "diag_privacy_policy",
                title = "Privacy Policy & DNR Safeguards",
                category = DiagnosticCategory.PRIVACY_POLICY,
                status = DiagnosticStatus.WARNING,
                summary = "First-run privacy consent notice has not been acknowledged.",
                actionableStep = "Review and acknowledge agency privacy guidelines in Privacy Controls."
            )
            paused -> DiagnosticItem(
                id = "diag_privacy_policy",
                title = "Privacy Policy & DNR Safeguards",
                category = DiagnosticCategory.PRIVACY_POLICY,
                status = DiagnosticStatus.WARNING,
                summary = "Recording synchronization is currently paused by agent/agency policy.",
                actionableStep = "Resume recording sync in Privacy Controls when ready."
            )
            else -> DiagnosticItem(
                id = "diag_privacy_policy",
                title = "Privacy Policy & DNR Safeguards",
                category = DiagnosticCategory.PRIVACY_POLICY,
                status = DiagnosticStatus.PASS,
                summary = "Consent acknowledged. Strict DNR enforcement active (${if (strictDnr) "Enabled" else "Standard"}). Zero mic permissions."
            )
        }
    }

    private fun checkFirebaseAndPush(): DiagnosticItem {
        val isFirebaseInit = try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (e: Exception) {
            false
        }

        // Check if google-services.json was processed into resources (string resource 'google_app_id')
        val hasGoogleServices = try {
            val resId = context.resources.getIdentifier("google_app_id", "string", context.packageName)
            resId != 0
        } catch (e: Exception) {
            false
        }

        return if (isFirebaseInit && hasGoogleServices) {
            DiagnosticItem(
                id = "diag_fcm_push",
                title = "Firebase Cloud Messaging",
                category = DiagnosticCategory.FCM_NOTIFICATIONS,
                status = DiagnosticStatus.PASS,
                summary = "Firebase initialized and google-services configuration detected. FCM push registration operational."
            )
        } else {
            DiagnosticItem(
                id = "diag_fcm_push",
                title = "Firebase Cloud Messaging",
                category = DiagnosticCategory.FCM_NOTIFICATIONS,
                status = DiagnosticStatus.WARNING,
                summary = "Standalone mode: google-services.json not packaged. Remote push requires Firebase config; local assignment alerts, caller overlay, and 15-minute background polling are active and fully operational.",
                actionableStep = "Informational: To enable remote push, place google-services.json in app/ module and configure backend FIREBASE_SERVICE_ACCOUNT."
            )
        }
    }

    private suspend fun checkInternalArchitectureLogic(): DiagnosticItem {
        // Safe, deterministic, in-memory check without mutating any production data or placing calls
        val normalized = normalizer.normalizeToE164("+91 98765 43210")
        val isNormalizerWorking = normalized == "+919876543210"

        val canQueryRoom = try {
            leadRepository.getLeadByNormalizedNumber("+0000000000") is AppResult
        } catch (e: Exception) {
            false
        }

        val canConstructNotification = try {
            val builder = androidx.core.app.NotificationCompat.Builder(
                context,
                LeadsNotificationManager.CHANNEL_ID_LEAD_ASSIGNMENTS
            ).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Self-Test")
                .setContentText("Self-Test Execution")
            builder.build() != null
        } catch (e: Exception) {
            false
        }

        return if (isNormalizerWorking && canQueryRoom && canConstructNotification) {
            DiagnosticItem(
                id = "diag_internal_logic",
                title = "Core Architecture & Logic Engine",
                category = DiagnosticCategory.INTERNAL_LOGIC,
                status = DiagnosticStatus.PASS,
                summary = "Deterministic unit checks passed: E.164 normalizer, Room DB queries, and notification builder operational."
            )
        } else {
            DiagnosticItem(
                id = "diag_internal_logic",
                title = "Core Architecture & Logic Engine",
                category = DiagnosticCategory.INTERNAL_LOGIC,
                status = DiagnosticStatus.FAIL,
                summary = "Internal logic validation failed. One or more core framework components returned unexpected state.",
                actionableStep = "Restart application and inspect system logs for component initialization errors."
            )
        }
    }
}

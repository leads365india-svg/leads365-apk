package com.leads365.apk.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.leads365.apk.ui.theme.Leads365Theme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var workManagerScheduler: com.leads365.apk.work.WorkManagerScheduler

    @javax.inject.Inject
    lateinit var callProcessingManager: com.leads365.apk.calls.CallProcessingManager

    @javax.inject.Inject
    lateinit var callerIdentificationManager: com.leads365.apk.calls.CallerIdentificationManager

    @javax.inject.Inject
    lateinit var leadRepository: com.leads365.apk.data.repository.LeadRepository

    @javax.inject.Inject
    lateinit var authRepository: com.leads365.apk.data.repository.AuthRepository

    @javax.inject.Inject
    lateinit var fcmRegistrationManager: com.leads365.apk.notifications.FcmRegistrationManager

    @javax.inject.Inject
    lateinit var safRecordingSource: com.leads365.apk.recordings.SafRecordingSource

    private val viewModel: MainViewModel by viewModels()
    private val leadsViewModel: com.leads365.apk.ui.leads.LeadsViewModel by viewModels()

    private val safPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.setSafTreeUri(uri)
            safRecordingSource.notifyFolderUpdated()
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Phase 10: Initialize notification channels and register FCM token
        com.leads365.apk.notifications.LeadsNotificationManager.initChannels(this)
        fcmRegistrationManager.checkAndRegisterToken(applicationContext)

        // Phase 5: Schedule periodic sync (15-min) and trigger initial sync on app launch
        workManagerScheduler.schedulePeriodicLeadSync()
        workManagerScheduler.triggerOneTimeLeadSync()

        // Phase 11: Schedule daily heartbeat (24-hr) and trigger on-app-open telemetry
        workManagerScheduler.schedulePeriodicHeartbeat()
        workManagerScheduler.triggerOneTimeHeartbeat()

        // Phase 12: Resume any pending/retryable recording uploads on launch
        lifecycleScope.launch {
            try {
                workManagerScheduler.resumePendingUploads()
            } catch (e: Exception) {
                // Non-blocking background recovery
            }
        }

        // Phase 10: Wire CallStateReceiver state transitions for Caller ID overlay/heads-up
        com.leads365.apk.calls.CallStateReceiver.onCallStateChangedListener = { stateStr, number ->
            when (stateStr) {
                android.telephony.TelephonyManager.EXTRA_STATE_RINGING,
                android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    callerIdentificationManager.onCallStarted(applicationContext, number)
                }
                android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                    callerIdentificationManager.onCallEnded(applicationContext)
                }
            }
        }

        handleIncomingNotificationIntent(intent)

        setContent {
            Leads365Theme {
                com.leads365.apk.ui.navigation.MainAppShell(
                    viewModel = viewModel,
                    onRequestPermissions = { requestAppPermissions() },
                    onRequestDirectCallPermission = { requestDirectCallPermission() },
                    onRequestOverlayPermission = { openOverlaySettings() },
                    onRequestBatteryOptimization = { requestIgnoreBatteryOptimization() },
                    onOpenAppSettings = { openAppSettings() },
                    onPickSafFolder = { safPickerLauncher.launch(null) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
        safRecordingSource.notifyFolderUpdated()
        // If user returns to the app during the test call step, check if a call just completed
        if (viewModel.uiState.value.currentStep == WizardStep.TEST_CALL &&
            viewModel.uiState.value.recentCallEvent == null
        ) {
            viewModel.checkForRecentCall()
        }

        // Phase 6: Probe for any call that finished while the app was backgrounded
        val reader = com.leads365.apk.calls.CallLogReader(applicationContext)
        val recentCall = reader.findMostRecentCall(maxEndRecencyMs = 900_000L)
        if (recentCall != null) {
            lifecycleScope.launch {
                callProcessingManager.processCallEvent(recentCall)
            }
        }

        // Phase 12: Resume pending and retryable uploads across app reopening
        lifecycleScope.launch {
            try {
                workManagerScheduler.resumePendingUploads()
            } catch (e: Exception) {
                // Non-blocking recovery
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun requestDirectCallPermission() {
        permissionsLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                openAppSettings()
            }
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                openAppSettings()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingNotificationIntent(intent)
    }

    private fun handleIncomingNotificationIntent(intent: Intent?) {
        if (intent == null) return
        if (!authRepository.isLoggedIn()) return

        val destination = intent.getStringExtra(com.leads365.apk.notifications.LeadsNotificationManager.EXTRA_DESTINATION)
            ?: if (intent.action == com.leads365.apk.notifications.LeadsNotificationManager.ACTION_VIEW_FOLLOW_UPS) "followups" else null

        if (!destination.isNullOrBlank()) {
            viewModel.setPendingDestination(destination)
        }

        val leadId = intent.getStringExtra(com.leads365.apk.notifications.LeadsNotificationManager.EXTRA_LEAD_ID)
        if (!leadId.isNullOrBlank()) {
            lifecycleScope.launch {
                when (val res = leadRepository.getLeadById(leadId)) {
                    is com.leads365.apk.core.AppResult.Success -> {
                        res.data?.let { lead ->
                            leadsViewModel.selectLeadForDetail(lead)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general settings if application details intent fails
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}

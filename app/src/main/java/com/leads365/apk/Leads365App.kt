package com.leads365.apk

import android.app.Application
import android.util.Log
import com.leads365.apk.calls.CallSyncForegroundService
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.network.NetworkConnectivityMonitor
import com.leads365.apk.work.WorkManagerScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class Leads365App : Application() {

    @Inject
    lateinit var networkConnectivityMonitor: dagger.Lazy<NetworkConnectivityMonitor>

    @Inject
    lateinit var authRepository: dagger.Lazy<AuthRepository>

    @Inject
    lateinit var workManagerScheduler: dagger.Lazy<WorkManagerScheduler>

    override fun onCreate() {
        super.onCreate()
        try {
            networkConnectivityMonitor.get().startMonitoring()
        } catch (e: Exception) {
            Log.w("Leads365App", "Failed to start network connectivity monitor: ${e.message}")
        }

        try {
            if (authRepository.get().isLoggedIn()) {
                workManagerScheduler.get().schedulePeriodicLeadSync()
                CallSyncForegroundService.start(this)
            }
        } catch (e: Exception) {
            Log.w("Leads365App", "Failed to initialize background sync on app start: ${e.message}")
        }
    }
}

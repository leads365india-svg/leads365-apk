package com.leads365.apk

import android.app.Application
import com.leads365.apk.network.NetworkConnectivityMonitor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class Leads365App : Application() {

    @Inject
    lateinit var networkConnectivityMonitor: NetworkConnectivityMonitor

    override fun onCreate() {
        super.onCreate()
        try {
            networkConnectivityMonitor.startMonitoring()
        } catch (e: Exception) {
            android.util.Log.w("Leads365App", "Failed to start network connectivity monitor: ${e.message}")
        }
    }
}

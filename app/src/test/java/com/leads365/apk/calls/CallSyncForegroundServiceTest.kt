package com.leads365.apk.calls

import android.app.AlarmManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.leads365.apk.core.AppResult
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.data.repository.CallRepository
import com.leads365.apk.diagnostics.BackgroundHardeningHelper
import com.leads365.apk.network.models.AuthData
import com.leads365.apk.network.models.DeviceData
import com.leads365.apk.network.models.UserDto
import com.leads365.apk.work.WorkManagerScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallSyncForegroundServiceTest {

    private lateinit var context: Context
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    class TestAuthRepository(private var loggedIn: Boolean = true) : AuthRepository {
        private val _authState = MutableStateFlow<AuthState>(
            if (loggedIn) AuthState.Authenticated(
                user = UserDto("u1", "Agent", "agent@leads365.com", "agent", "active"),
                agency = null,
                deviceId = "dev1"
            ) else AuthState.Unauthenticated
        )
        override val authState: StateFlow<AuthState> = _authState
        override fun isLoggedIn(): Boolean = loggedIn
        fun setLoggedIn(value: Boolean) { loggedIn = value }
        override fun getCurrentUser(): UserDto? = null
        override fun getAgencyName(): String? = null
        override fun getDeviceId(): String? = null
        override suspend fun login(email: String, password: String): AppResult<AuthData> =
            AppResult.Success(AuthData("tok", "ref", 86400L, UserDto("u1", "Agent", email)))
        override suspend fun registerDevice(
            deviceModel: String, manufacturer: String, osVersion: String,
            sdkInt: Int, appVersionCode: Int, appVersionName: String, fcmToken: String?
        ): AppResult<DeviceData> = AppResult.Success(DeviceData("dev1", "ACTIVE", 1000L))
        override suspend fun updatePushToken(token: String): AppResult<Unit> = AppResult.Success(Unit)
        override fun getSavedFcmToken(): String? = null
        override fun saveFcmToken(token: String) {}
        override suspend fun logout(preservePendingUploads: Boolean): AppResult<Unit> = AppResult.Success(Unit)
        override fun invalidateSession(reason: String?) {}
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = shadowOf(alarmManager)
    }

    @Test
    fun startService_launchesServiceIntentWithStartAction() {
        CallSyncForegroundService.start(context)
        val nextServiceIntent = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull("Should issue intent to start service", nextServiceIntent)
        assertEquals(CallSyncForegroundService.ACTION_START, nextServiceIntent.action)
        assertEquals(CallSyncForegroundService::class.java.name, nextServiceIntent.component?.className)
    }

    @Test
    fun stopService_launchesServiceIntentWithStopAction() {
        CallSyncForegroundService.stop(context)
        val nextServiceIntent = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull("Should issue intent to stop service", nextServiceIntent)
        assertEquals(CallSyncForegroundService.ACTION_STOP, nextServiceIntent.action)
        assertEquals(CallSyncForegroundService::class.java.name, nextServiceIntent.component?.className)
    }

    @Test
    fun unauthenticated_onStartCommand_returnsNotStickyAndStopsSelf() {
        val service = Robolectric.buildService(CallSyncForegroundService::class.java).get()
        val authRepo = TestAuthRepository(loggedIn = false)
        service.authRepository = authRepo
        service.workManagerScheduler = dagger.Lazy { WorkManagerScheduler(context, WorkManager.getInstance(context)) }

        val intent = Intent(context, CallSyncForegroundService::class.java).apply {
            action = CallSyncForegroundService.ACTION_START
        }

        val result = service.onStartCommand(intent, 0, 1)

        assertEquals("Unauthenticated start must return START_NOT_STICKY", Service.START_NOT_STICKY, result)
        assertFalse("Service should not be running when unauthenticated", CallSyncForegroundService.isRunning)
    }

    @Test
    fun authenticated_onStartCommand_returnsStickyAndRuns() {
        val service = Robolectric.buildService(CallSyncForegroundService::class.java).get()
        val authRepo = TestAuthRepository(loggedIn = true)
        service.authRepository = authRepo
        service.workManagerScheduler = dagger.Lazy { WorkManagerScheduler(context, WorkManager.getInstance(context)) }

        val intent = Intent(context, CallSyncForegroundService::class.java).apply {
            action = CallSyncForegroundService.ACTION_START
        }

        val result = service.onStartCommand(intent, 0, 1)

        assertEquals("Authenticated start must return START_STICKY", Service.START_STICKY, result)
        assertTrue("Service should be running when authenticated", CallSyncForegroundService.isRunning)
    }

    @Test
    fun actionStop_onStartCommand_returnsNotStickyAndStops() {
        val service = Robolectric.buildService(CallSyncForegroundService::class.java).get()
        val authRepo = TestAuthRepository(loggedIn = true)
        service.authRepository = authRepo
        service.workManagerScheduler = dagger.Lazy { WorkManagerScheduler(context, WorkManager.getInstance(context)) }

        val intent = Intent(context, CallSyncForegroundService::class.java).apply {
            action = CallSyncForegroundService.ACTION_STOP
        }

        val result = service.onStartCommand(intent, 0, 1)

        assertEquals("ACTION_STOP must return START_NOT_STICKY", Service.START_NOT_STICKY, result)
        assertFalse("Service should not be running after stop", CallSyncForegroundService.isRunning)
    }

    @Test
    fun onTaskRemoved_whenAuthenticated_schedulesRestartAlarm() {
        val service = Robolectric.buildService(CallSyncForegroundService::class.java).get()
        val authRepo = TestAuthRepository(loggedIn = true)
        service.authRepository = authRepo
        service.workManagerScheduler = dagger.Lazy { WorkManagerScheduler(context, WorkManager.getInstance(context)) }

        service.onTaskRemoved(Intent())

        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertFalse("Must schedule at least one alarm when app is swiped from recents", scheduledAlarms.isEmpty())
        val restartAlarm = scheduledAlarms.first()
        assertNotNull("Scheduled alarm operation must not be null", restartAlarm.operation)
    }

    @Test
    fun onTaskRemoved_whenUnauthenticated_doesNotScheduleRestartAlarm() {
        val service = Robolectric.buildService(CallSyncForegroundService::class.java).get()
        val authRepo = TestAuthRepository(loggedIn = false)
        service.authRepository = authRepo
        service.workManagerScheduler = dagger.Lazy { WorkManagerScheduler(context, WorkManager.getInstance(context)) }

        service.onTaskRemoved(Intent())

        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Must NOT schedule restart alarm when user is unauthenticated", scheduledAlarms.isEmpty())
    }

    @Test
    fun hasNotificationPermission_returnsExpectedBoolean() {
        // Under Robolectric SDK 34, verifies without throwing
        val hasPerm = CallSyncForegroundService.hasNotificationPermission(context)
        assertNotNull(hasPerm)
    }

    @Test
    fun batteryOptimizationIntent_includesPackageUri() {
        val intent = BackgroundHardeningHelper.createBatteryOptimizationIntent(context)
        assertNotNull(intent)
        assertTrue(
            "Action must be request ignore or ignore battery optimizations",
            intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ||
            intent.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        )
        if (intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            assertEquals("package:${context.packageName}", intent.dataString)
        }
    }
}

package com.leads365.apk.contacts

import android.content.Context
import android.content.SharedPreferences
import android.database.MatrixCursor
import android.provider.ContactsContract
import com.leads365.apk.core.AppResult
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.network.api.ContactsSyncApiService
import com.leads365.apk.network.models.AuthData
import com.leads365.apk.network.models.DeviceData
import com.leads365.apk.network.models.SyncPhoneContactsData
import com.leads365.apk.network.models.SyncPhoneContactsRequest
import com.leads365.apk.network.models.SyncPhoneContactsResponse
import com.leads365.apk.network.models.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PhoneContactsSyncTest {

    private lateinit var normalizer: PhoneNumberNormalizer
    private lateinit var syncManager: PhoneContactsSyncManager
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: FakeContext
    private lateinit var fakeApiService: FakeContactsSyncApiService
    private lateinit var fakeAuthRepo: FakeAuthRepository

    @Before
    fun setUp() {
        normalizer = PhoneNumberNormalizer("IN")
        fakePrefs = FakeSharedPreferences()
        fakeContext = FakeContext(fakePrefs, hasContactsPermission = true)
        fakeApiService = FakeContactsSyncApiService()
        fakeAuthRepo = FakeAuthRepository(loggedIn = true)

        syncManager = PhoneContactsSyncManager(
            context = fakeContext,
            contactsSyncApiService = dagger.Lazy { fakeApiService },
            authRepository = dagger.Lazy { fakeAuthRepo },
            normalizer = normalizer
        )
    }

    @Test
    fun `test phone number normalization formats to E164 properly`() {
        // Indian 10-digit number without country code
        assertEquals("+919876543210", syncManager.normalizeContactPhoneNumber("9876543210"))

        // Indian number with spaces and dashes
        assertEquals("+919876543210", syncManager.normalizeContactPhoneNumber("+91 98765-43210"))

        // Indian number with leading zero
        assertEquals("+919876543210", syncManager.normalizeContactPhoneNumber("09876543210"))

        // Already formatted E164
        assertEquals("+919876543210", syncManager.normalizeContactPhoneNumber("+919876543210"))

        // International format (e.g. US)
        val usNumber = syncManager.normalizeContactPhoneNumber("+1 415 555 2671")
        assertEquals("+14155552671", usNumber)

        // Invalid / empty numbers return null
        assertNull(syncManager.normalizeContactPhoneNumber(null))
        assertNull(syncManager.normalizeContactPhoneNumber(""))
        assertNull(syncManager.normalizeContactPhoneNumber("   "))
        assertNull(syncManager.normalizeContactPhoneNumber("123"))
    }

    @Test
    fun `test cursor extraction normalizes and deduplicates duplicate numbers`() {
        val rawNumbers = listOf(
            "9876543210",
            "+91 98765 43210",
            "09876543210",
            "+919876543210",
            "+919123456789",
            "invalid_phone",
            "123",
            ""
        )
        val cursor = createFakeCursor(rawNumbers)

        val result = syncManager.extractPhoneNumbersFromCursor(cursor)

        // All 4 variations of 9876543210 must collapse into 1 unique entry
        assertEquals(2, result.size)
        assertTrue(result.contains("+919876543210"))
        assertTrue(result.contains("+919123456789"))
        assertFalse(result.contains("invalid_phone"))
        assertFalse(result.contains("123"))

        // Also test direct collection extraction
        val directResult = syncManager.extractPhoneNumbers(rawNumbers)
        assertEquals(result, directResult)
    }

    @Test
    fun `test deterministic SHA-256 hash produces identical hash regardless of item order`() {
        val list1 = listOf("+919876543210", "+919123456789", "+918000000001")
        val list2 = listOf("+918000000001", "+919876543210", "+919123456789")

        val hash1 = syncManager.computeContactsHash(list1)
        val hash2 = syncManager.computeContactsHash(list2)

        assertEquals("Hash must be order-independent", hash1, hash2)
        assertEquals(64, hash1.length) // SHA-256 hex string length
    }

    @Test
    fun `test SHA-256 hash changes when contact list changes`() {
        val originalList = listOf("+919876543210", "+919123456789")
        val modifiedList = listOf("+919876543210", "+919123456789", "+917777777777")

        val hashOriginal = syncManager.computeContactsHash(originalList)
        val hashModified = syncManager.computeContactsHash(modifiedList)

        assertNotEquals("Hash must change when contacts are added or removed", hashOriginal, hashModified)
    }

    @Test
    fun `test shouldSync triggers on first sync when no prior hash exists`() {
        val currentHash = "abc123hash"
        val should = syncManager.shouldSync(
            currentHash = currentHash,
            lastHash = null,
            lastSyncTimestamp = 0L,
            currentTimeMs = 1000000L,
            force = false
        )
        assertTrue("First sync with null lastHash must always trigger", should)
    }

    @Test
    fun `test shouldSync triggers immediately when contact list hash changes`() {
        val currentHash = "new_hash_456"
        val lastHash = "old_hash_123"
        val now = 1000000L
        val justSynced = now - 5000L // 5 seconds ago

        val should = syncManager.shouldSync(
            currentHash = currentHash,
            lastHash = lastHash,
            lastSyncTimestamp = justSynced,
            currentTimeMs = now,
            force = false
        )
        assertTrue("Hash change must trigger sync even if last sync was recent", should)
    }

    @Test
    fun `test shouldSync skips when hash unchanged and within 6 hours`() {
        val currentHash = "identical_hash"
        val lastHash = "identical_hash"
        val now = 1000000000L
        val fiveHoursAgo = now - (5 * 60 * 60 * 1000L)

        val should = syncManager.shouldSync(
            currentHash = currentHash,
            lastHash = lastHash,
            lastSyncTimestamp = fiveHoursAgo,
            currentTimeMs = now,
            force = false
        )
        assertFalse("Unchanged hash within 6 hours must be skipped", should)
    }

    @Test
    fun `test shouldSync triggers after 6 hours even if hash is unchanged`() {
        val currentHash = "identical_hash"
        val lastHash = "identical_hash"
        val now = 1000000000L
        val sixHoursAndOneMinuteAgo = now - (6 * 60 * 60 * 1000L + 60000L)

        val should = syncManager.shouldSync(
            currentHash = currentHash,
            lastHash = lastHash,
            lastSyncTimestamp = sixHoursAndOneMinuteAgo,
            currentTimeMs = now,
            force = false
        )
        assertTrue("Periodic sync must trigger once 6 hours have elapsed", should)
    }

    @Test
    fun `test shouldSync triggers when force is true regardless of hash or elapsed time`() {
        val currentHash = "identical_hash"
        val lastHash = "identical_hash"
        val now = 1000000000L
        val tenSecondsAgo = now - 10000L

        val should = syncManager.shouldSync(
            currentHash = currentHash,
            lastHash = lastHash,
            lastSyncTimestamp = tenSecondsAgo,
            currentTimeMs = now,
            force = true
        )
        assertTrue("force=true must always trigger sync", should)
    }

    @Test
    fun `test syncContacts skips when user is unauthenticated`() = runBlocking {
        fakeAuthRepo.loggedIn = false

        val result = syncManager.syncContacts()

        assertTrue(result is ContactsSyncResult.Skipped)
        assertEquals("User not authenticated", (result as ContactsSyncResult.Skipped).reason)
        assertNull(fakeApiService.lastRequest)
    }

    @Test
    fun `test syncContacts skips when READ_CONTACTS permission is missing`() = runBlocking {
        fakeContext.hasContactsPermission = false

        val result = syncManager.syncContacts()

        assertTrue(result is ContactsSyncResult.Skipped)
        assertTrue((result as ContactsSyncResult.Skipped).reason.contains("permission"))
        assertNull(fakeApiService.lastRequest)
    }

    @Test
    fun `test saveLastSync and clearStoredSyncState`() {
        val testHash = "hash_xyz_789"
        val timestamp = 123456789L

        syncManager.saveLastSync(testHash, timestamp)
        assertEquals(testHash, syncManager.getLastSyncHash())
        assertEquals(timestamp, syncManager.getLastSyncTimestamp())

        syncManager.clearStoredSyncState()
        assertNull(syncManager.getLastSyncHash())
        assertEquals(0L, syncManager.getLastSyncTimestamp())
    }

    // --- Fake Test Implementations ---

    private fun createFakeCursor(numbers: List<String?>): android.database.Cursor {
        var index = -1
        val invocationHandler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.name) {
                "moveToNext" -> {
                    index++
                    index < numbers.size
                }
                "getColumnIndex" -> 0
                "getString" -> {
                    if (index in numbers.indices) numbers[index] else null
                }
                "close" -> null
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            android.database.Cursor::class.java.classLoader,
            arrayOf(android.database.Cursor::class.java),
            invocationHandler
        ) as android.database.Cursor
    }

    class FakeContext(
        private val fakePrefs: FakeSharedPreferences,
        var hasContactsPermission: Boolean = true
    ) : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
            return if (permission == android.Manifest.permission.READ_CONTACTS && hasContactsPermission) {
                android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                android.content.pm.PackageManager.PERMISSION_DENIED
            }
        }
    }

    class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null && value != null) prefs.map[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null && values != null) prefs.map[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) prefs.map[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) prefs.map[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) prefs.map[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) prefs.map[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) prefs.map.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                prefs.map.clear()
                return this
            }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    class FakeContactsSyncApiService : ContactsSyncApiService {
        var lastRequest: SyncPhoneContactsRequest? = null
        var shouldSucceed: Boolean = true
        var returnError: String? = null

        override suspend fun syncContacts(request: SyncPhoneContactsRequest): SyncPhoneContactsResponse {
            lastRequest = request
            return if (shouldSucceed) {
                SyncPhoneContactsResponse(
                    success = true,
                    data = SyncPhoneContactsData(syncedCount = request.phones.size, tenantId = "t1")
                )
            } else {
                SyncPhoneContactsResponse(
                    success = false,
                    error = returnError ?: "Server error"
                )
            }
        }
    }

    class FakeAuthRepository(
        var loggedIn: Boolean = true
    ) : AuthRepository {
        private val _authState = MutableStateFlow<AuthState>(
            if (loggedIn) AuthState.Authenticated(
                user = UserDto("u1", "test", "test@test.com", "agent", "active"),
                agency = null,
                deviceId = "dev1"
            )
            else AuthState.Unauthenticated
        )
        override val authState: StateFlow<AuthState> = _authState
        override fun isLoggedIn(): Boolean = loggedIn
        override fun getCurrentUser(): UserDto? = null
        override fun getAgencyName(): String? = null
        override fun getDeviceId(): String? = null
        override suspend fun login(email: String, password: String): AppResult<AuthData> =
            AppResult.Success(AuthData("tok", "ref", 86400L, UserDto("u1", "test", "test@test.com")))
        override suspend fun registerDevice(
            deviceModel: String, manufacturer: String, osVersion: String,
            sdkInt: Int, appVersionCode: Int, appVersionName: String, fcmToken: String?
        ): AppResult<DeviceData> =
            AppResult.Success(DeviceData("dev1", "ACTIVE", 1000L))
        override suspend fun updatePushToken(token: String): AppResult<Unit> = AppResult.Success(Unit)
        override fun getSavedFcmToken(): String? = null
        override fun saveFcmToken(token: String) {}
        override suspend fun logout(preservePendingUploads: Boolean): AppResult<Unit> = AppResult.Success(Unit)
        override fun invalidateSession(reason: String?) {}
    }
}

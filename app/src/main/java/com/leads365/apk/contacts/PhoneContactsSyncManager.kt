package com.leads365.apk.contacts

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.data.repository.AuthRepository
import com.leads365.apk.network.api.ContactsSyncApiService
import com.leads365.apk.network.models.SyncPhoneContactsRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneContactsSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactsSyncApiService: dagger.Lazy<ContactsSyncApiService>,
    private val authRepository: dagger.Lazy<AuthRepository>,
    private val normalizer: PhoneNumberNormalizer
) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Reads and normalizes all phone numbers saved in the device address book.
     * Deduplicates multiple contacts with the same normalized number.
     */
    fun readDeviceContacts(): Set<String> {
        if (!hasPermission()) {
            Log.w(TAG, "Cannot read contacts: READ_CONTACTS permission not granted")
            return emptySet()
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )
            extractPhoneNumbersFromCursor(cursor)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying ContactsContract: ${e.message}", e)
            emptySet()
        }
    }

    /**
     * Extracts, normalizes, and deduplicates phone numbers from a Contacts cursor.
     * Open for testability with simulated cursors.
     */
    fun extractPhoneNumbersFromCursor(cursor: Cursor?): Set<String> {
        if (cursor == null) return emptySet()
        val rawList = mutableListOf<String?>()
        cursor.use { c ->
            val numberIndex = try {
                c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            } catch (e: Exception) {
                -1
            }
            val targetIndex = if (numberIndex >= 0) numberIndex else 0
            while (c.moveToNext()) {
                try {
                    rawList.add(c.getString(targetIndex))
                } catch (e: Exception) {
                    // Ignore cursor read errors
                }
            }
        }
        return extractPhoneNumbers(rawList)
    }

    /**
     * Normalizes and deduplicates a collection of raw phone number strings.
     */
    fun extractPhoneNumbers(rawNumbers: Collection<String?>): Set<String> {
        val phoneNumbers = LinkedHashSet<String>()
        for (raw in rawNumbers) {
            val normalized = normalizeContactPhoneNumber(raw)
            if (!normalized.isNullOrBlank()) {
                phoneNumbers.add(normalized)
            }
        }
        return phoneNumbers
    }

    /**
     * Normalizes a phone number to standard E.164 (or best-effort E.164 with country code)
     * suitable for backend lead matching and contact exclusion.
     */
    fun normalizeContactPhoneNumber(rawNumber: String?): String? {
        if (rawNumber.isNullOrBlank()) return null
        val digits = normalizer.extractDigitsOnly(rawNumber)
        if (digits.length < 10) return null

        val normalized = normalizer.normalizeToE164(rawNumber)
        if (!normalized.isNullOrBlank()) {
            return normalized
        }
        // Fallback for non-standard entries with at least 10 digits
        return when {
            digits.length == 10 -> "+91$digits"
            digits.length == 11 && digits.startsWith("0") -> "+91${digits.drop(1)}"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            digits.length > 10 -> "+$digits"
            else -> null
        }
    }

    /**
     * Computes a deterministic SHA-256 hash of a list/set of phone numbers.
     * Numbers are sorted so order variations do not change the resulting hash.
     */
    fun computeContactsHash(phones: Collection<String>): String {
        val sorted = phones.sorted()
        val content = sorted.joinToString("\n")
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Evaluates whether a contact sync should be performed based on hash change,
     * time elapsed since last successful sync, or a forced sync request.
     */
    fun shouldSync(
        currentHash: String,
        lastHash: String?,
        lastSyncTimestamp: Long,
        currentTimeMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): Boolean {
        if (force) return true
        if (lastHash == null || lastHash != currentHash) {
            return true // Contacts list changed or never synced
        }
        val elapsed = currentTimeMs - lastSyncTimestamp
        // Sync if at least 6 hours elapsed or clock was set backwards
        return elapsed >= SIX_HOURS_MS || elapsed < 0L
    }

    /**
     * Convenience method checking stored state in SharedPreferences.
     */
    fun shouldSyncStored(
        currentHash: String,
        currentTimeMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): Boolean {
        val lastHash = prefs.getString(KEY_LAST_SYNC_HASH, null)
        val lastSyncTimestamp = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
        return shouldSync(
            currentHash = currentHash,
            lastHash = lastHash,
            lastSyncTimestamp = lastSyncTimestamp,
            currentTimeMs = currentTimeMs,
            force = force
        )
    }

    /**
     * Performs contacts sync against the CRM backend:
     * 1. Validates authentication and permission.
     * 2. Reads and normalizes all phone contacts.
     * 3. Computes SHA-256 hash and checks if upload can be skipped (unchanged & < 6 hours).
     * 4. Sends `{ "phones": [...] }` to `POST /api/integrations/whatsapp-qr/sync-contacts`.
     * 5. Saves hash and timestamp upon success.
     */
    suspend fun syncContacts(force: Boolean = false): ContactsSyncResult {
        val auth = authRepository.get()
        if (!auth.isLoggedIn()) {
            Log.d(TAG, "User not logged in; skipping phone contacts sync")
            return ContactsSyncResult.Skipped("User not authenticated")
        }

        if (!hasPermission()) {
            Log.d(TAG, "READ_CONTACTS permission not granted; skipping phone contacts sync")
            return ContactsSyncResult.Skipped("READ_CONTACTS permission not granted")
        }

        val phones = readDeviceContacts()
        val currentHash = computeContactsHash(phones)

        if (!shouldSyncStored(currentHash, force = force)) {
            Log.i(TAG, "Contacts hash unchanged and synced within last 6 hours; skipping upload")
            return ContactsSyncResult.Skipped(
                reason = "Contacts hash unchanged and synced within last 6 hours",
                hash = currentHash
            )
        }

        Log.i(TAG, "Syncing ${phones.size} phone contacts to CRM backend (force=$force, hash=${currentHash.take(8)}...)")

        return try {
            val request = SyncPhoneContactsRequest(
                phones = phones.toList(),
                replaceAll = false
            )
            val response = contactsSyncApiService.get().syncContacts(request)
            if (response.success) {
                val now = System.currentTimeMillis()
                saveLastSync(currentHash, now)
                val syncedCount = response.data?.syncedCount ?: phones.size
                Log.i(TAG, "Successfully synced $syncedCount contacts to backend")
                ContactsSyncResult.Success(
                    syncedCount = syncedCount,
                    totalContacts = phones.size,
                    hash = currentHash
                )
            } else {
                val err = response.error ?: response.message ?: "Failed to sync contacts"
                Log.w(TAG, "Backend returned error syncing contacts: $err")
                ContactsSyncResult.Failed(err)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during contacts sync: ${e.message}", e)
            ContactsSyncResult.Failed(e.message ?: "Network error during contacts sync")
        }
    }

    fun saveLastSync(hash: String, timestamp: Long) {
        prefs.edit()
            .putString(KEY_LAST_SYNC_HASH, hash)
            .putLong(KEY_LAST_SYNC_TIMESTAMP, timestamp)
            .apply()
    }

    fun getLastSyncHash(): String? = prefs.getString(KEY_LAST_SYNC_HASH, null)

    fun getLastSyncTimestamp(): Long = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)

    fun clearStoredSyncState() {
        prefs.edit()
            .remove(KEY_LAST_SYNC_HASH)
            .remove(KEY_LAST_SYNC_TIMESTAMP)
            .apply()
    }

    companion object {
        private const val TAG = "PhoneContactsSync"
        const val PREFS_NAME = "contacts_sync_prefs"
        const val KEY_LAST_SYNC_HASH = "last_contacts_sync_hash"
        const val KEY_LAST_SYNC_TIMESTAMP = "last_contacts_sync_timestamp"
        const val KEY_CONTACTS_PERMISSION_PROMPTED = "has_prompted_contacts_permission"

        // Throttle interval: 6 hours
        const val SIX_HOURS_MS = 6 * 60 * 60 * 1000L
    }
}

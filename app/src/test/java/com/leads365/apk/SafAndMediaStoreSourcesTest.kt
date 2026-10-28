package com.leads365.apk

import com.leads365.apk.core.RecordingDiscoveryMethod
import com.leads365.apk.recordings.SafRecordingSource
import org.junit.Assert.*
import org.junit.Test

class SafAndMediaStoreSourcesTest {

    @Test
    fun testSafConfiguredWhenTreeUriProvided() {
        val testUriString = "content://com.android.externalstorage.documents/tree/primary%3ARecordings"
        var currentUri: String? = testUriString

        val safSource = SafRecordingSource(
            context = object : android.content.ContextWrapper(null) {},
            getTreeUriString = { currentUri }
        )

        assertTrue("SAF source should be configured when URI is set", safSource.isConfigured())
        assertEquals(RecordingDiscoveryMethod.STORAGE_ACCESS_FRAMEWORK, safSource.discoveryMethod)

        // Revoke URI
        currentUri = null
        assertFalse("SAF source should not be configured when URI is cleared", safSource.isConfigured())
    }

    @Test
    fun testSafAccessVerificationFailsWhenPermissionNotPersisted() {
        val testUriString = "content://com.android.externalstorage.documents/tree/primary%3ARecordings"

        val safSourceRevoked = SafRecordingSource(
            context = object : android.content.ContextWrapper(null) {},
            getTreeUriString = { testUriString },
            hasPersistedPermission = { false } // Simulates revoked/missing persisted grant
        )

        assertTrue(safSourceRevoked.isConfigured())
        assertFalse("SAF source should report inaccessible when grant was revoked or not persisted", safSourceRevoked.isAccessible())

        val safSourceActive = SafRecordingSource(
            context = object : android.content.ContextWrapper(null) {},
            getTreeUriString = { testUriString },
            hasPersistedPermission = { true } // Simulates valid persisted grant
        )
        assertTrue("SAF source should report accessible when grant is active", safSourceActive.isAccessible())
    }
}

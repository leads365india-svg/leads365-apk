package com.leads365.apk.recordings.di

import android.content.Context
import android.net.Uri
import android.util.Log
import com.leads365.apk.core.PhoneNumberNormalizer
import com.leads365.apk.recordings.MediaStoreRecordingSource
import com.leads365.apk.recordings.SafRecordingSource
import com.leads365.apk.recordings.TargetedRecordingMatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RecordingsModule {

    private const val TAG = "RecordingsModule"
    const val PREFS_NAME = "com.leads365.apk.preferences"
    const val KEY_SAF_URI = "key_saf_tree_uri"

    @Provides
    @Singleton
    fun provideTargetedRecordingMatcher(
        normalizer: PhoneNumberNormalizer
    ): TargetedRecordingMatcher {
        return TargetedRecordingMatcher(
            normalizer = normalizer,
            minAcceptanceScore = 0.50,
            ambiguityDeltaThreshold = 0.15
        )
    }

    @Provides
    @Singleton
    fun provideMediaStoreRecordingSource(
        @ApplicationContext context: Context
    ): MediaStoreRecordingSource {
        return MediaStoreRecordingSource(context)
    }

    @Provides
    @Singleton
    fun provideSafRecordingSource(
        @ApplicationContext context: Context
    ): SafRecordingSource {
        return SafRecordingSource(
            context = context,
            getTreeUri = {
                // 1. Check canonical preference
                val prefsCanonical = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val rawCanonical = prefsCanonical.getString(KEY_SAF_URI, null)

                // 2. Check phase 1 wizard preference
                val prefsPhase1 = context.getSharedPreferences("leads365_phase1_prefs", Context.MODE_PRIVATE)
                val rawPhase1 = prefsPhase1.getString(KEY_SAF_URI, null)

                // 3. Check legacy prefs
                val prefsLegacy = context.getSharedPreferences("leads365_prefs", Context.MODE_PRIVATE)
                val rawLegacy = prefsLegacy.getString("saf_tree_uri", null)

                val rawUri = rawCanonical ?: rawPhase1 ?: rawLegacy
                if (!rawUri.isNullOrBlank()) {
                    try {
                        Uri.parse(rawUri)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse saved SAF URI: $rawUri", e)
                        null
                    }
                } else {
                    // 4. Fallback to active persisted URI permission from Android system
                    val persisted = context.contentResolver.persistedUriPermissions
                        .firstOrNull { it.isReadPermission }?.uri
                    if (persisted != null) {
                        Log.i(TAG, "Resolved SAF tree URI from persistedUriPermissions fallback: $persisted")
                        // Sync back to canonical prefs
                        prefsCanonical.edit().putString(KEY_SAF_URI, persisted.toString()).apply()
                    }
                    persisted
                }
            }
        )
    }
}

package com.lucid.appstore.download.config

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class Flags(
    /** Kill switch. Off: no work is scheduled, any run is cancelled and the AIDL reports disabled. */
    val downloadServiceEnabled: Boolean,
)

/** Feature flags. Replace the implementation with a remote-config source when one exists. */
interface FeatureFlags {
    val flags: Flow<Flags>
    suspend fun setDownloadServiceEnabled(enabled: Boolean)
}

class DataStoreFeatureFlags(context: Context, private val defaultEnabled: Boolean) : FeatureFlags {
    private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("download_flags") }

    override val flags: Flow<Flags> =
        store.data.map { Flags(downloadServiceEnabled = it[ENABLED] ?: defaultEnabled) }.distinctUntilChanged()

    override suspend fun setDownloadServiceEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("download_service_enabled")
    }
}

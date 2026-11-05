package com.lucid.appmanager.download.config

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lucid.appmanager.install.InstallMode
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class Flags(
    /** Kill switch. Off: no work is scheduled, any run is cancelled and the AIDL reports disabled. */
    val downloadServiceEnabled: Boolean,
    /** Which install path a run uses (FR-52). Read once at install time, so a mid-run flip lands next run. */
    val installMode: InstallMode = InstallMode.PACKAGE_MANAGER,
)

/** Feature flags. Replace the implementation with a remote-config source when one exists. */
interface FeatureFlags {
    val flags: Flow<Flags>
    suspend fun setDownloadServiceEnabled(enabled: Boolean)
    suspend fun setInstallMode(mode: InstallMode)
}

class DataStoreFeatureFlags(
    context: Context,
    private val defaultEnabled: Boolean,
    private val defaultInstallMode: InstallMode = InstallMode.PACKAGE_MANAGER,
) : FeatureFlags {
    private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("download_flags") }

    override val flags: Flow<Flags> = store.data.map {
        Flags(
            downloadServiceEnabled = it[ENABLED] ?: defaultEnabled,
            installMode = it[INSTALL_MODE]?.let(InstallMode::parse) ?: defaultInstallMode,
        )
    }.distinctUntilChanged()

    override suspend fun setDownloadServiceEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    override suspend fun setInstallMode(mode: InstallMode) {
        store.edit { it[INSTALL_MODE] = mode.name }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("download_service_enabled")
        val INSTALL_MODE = stringPreferencesKey("install_mode")
    }
}

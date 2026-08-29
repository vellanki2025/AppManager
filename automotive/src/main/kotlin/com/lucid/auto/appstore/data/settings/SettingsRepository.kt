package com.lucid.auto.appstore.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lucid.auto.appstore.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lucid_appstore_settings")

/**
 * Persists the two user-controlled settings.
 *
 * `autoUpdateEnabled` is the single boolean flag from the requirement. It defaults to **false**:
 * the vehicle never installs anything in the background unless the owner opts in.
 */
interface SettingsRepository {
    val autoUpdateEnabled: Flow<Boolean>
    val catalogBaseUrl: Flow<String>
    suspend fun setAutoUpdateEnabled(enabled: Boolean)
    suspend fun setCatalogBaseUrl(url: String)
    suspend fun isAutoUpdateEnabledNow(): Boolean = autoUpdateEnabled.first()
    suspend fun catalogBaseUrlNow(): String = catalogBaseUrl.first()
}

class DataStoreSettingsRepository(
    private val context: Context,
    private val defaultBaseUrl: String = BuildConfig.DEFAULT_CATALOG_BASE_URL,
) : SettingsRepository {

    private val preferences: Flow<Preferences> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    override val autoUpdateEnabled: Flow<Boolean> =
        preferences.map { it[KEY_AUTO_UPDATE] ?: false }

    override val catalogBaseUrl: Flow<String> =
        preferences.map { it[KEY_BASE_URL]?.takeIf(String::isNotBlank) ?: defaultBaseUrl }

    override suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_UPDATE] = enabled }
    }

    override suspend fun setCatalogBaseUrl(url: String) {
        context.dataStore.edit { it[KEY_BASE_URL] = url.trim().trimEnd('/') }
    }

    private companion object {
        val KEY_AUTO_UPDATE = booleanPreferencesKey("auto_update_enabled")
        val KEY_BASE_URL = stringPreferencesKey("catalog_base_url")
    }
}

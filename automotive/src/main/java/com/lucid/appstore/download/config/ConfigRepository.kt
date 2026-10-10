package com.lucid.appstore.download.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.lucid.appstore.download.model.Config
import com.lucid.appstore.download.model.NetworkMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalTime
import kotlin.time.Duration.Companion.seconds

interface ConfigRepository {
    val config: Flow<Config>
    suspend fun update(transform: (Config) -> Config)
}

/** DataStore-backed [Config]. Missing keys fall back to the [Config] defaults. */
class DataStoreConfigRepository(context: Context) : ConfigRepository {
    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("download_config") }

    override val config: Flow<Config> = store.data.map { it.toConfig() }.distinctUntilChanged()

    override suspend fun update(transform: (Config) -> Config) {
        store.edit { prefs -> prefs.write(transform(prefs.toConfig())) }
    }

    private fun Preferences.toConfig(): Config {
        val d = Config()
        return Config(
            scheduleTime = this[SCHEDULE_TIME]?.let(LocalTime::parse) ?: d.scheduleTime,
            networkMode = this[NETWORK_MODE]?.let(NetworkMode::valueOf) ?: d.networkMode,
            allowTethered = this[ALLOW_TETHERED] ?: d.allowTethered,
            requireParkingBrake = this[REQUIRE_PARKING_BRAKE] ?: d.requireParkingBrake,
            batteryThresholdPercent = this[BATTERY_THRESHOLD] ?: d.batteryThresholdPercent,
            batteryResumeMarginPercent = this[BATTERY_MARGIN] ?: d.batteryResumeMarginPercent,
            resumeStabilityWindow = this[STABILITY_SECONDS]?.seconds ?: d.resumeStabilityWindow,
            recheckInterval = this[RECHECK_SECONDS]?.seconds ?: d.recheckInterval,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(c: Config) {
        this[SCHEDULE_TIME] = c.scheduleTime.toString()
        this[NETWORK_MODE] = c.networkMode.name
        this[ALLOW_TETHERED] = c.allowTethered
        this[REQUIRE_PARKING_BRAKE] = c.requireParkingBrake
        this[BATTERY_THRESHOLD] = c.batteryThresholdPercent
        this[BATTERY_MARGIN] = c.batteryResumeMarginPercent
        this[STABILITY_SECONDS] = c.resumeStabilityWindow.inWholeSeconds
        this[RECHECK_SECONDS] = c.recheckInterval.inWholeSeconds
    }

    private companion object {
        val SCHEDULE_TIME = stringPreferencesKey("schedule_time")
        val NETWORK_MODE = stringPreferencesKey("network_mode")
        val ALLOW_TETHERED = booleanPreferencesKey("allow_tethered")
        val REQUIRE_PARKING_BRAKE = booleanPreferencesKey("require_parking_brake")
        val BATTERY_THRESHOLD = intPreferencesKey("battery_threshold")
        val BATTERY_MARGIN = intPreferencesKey("battery_margin")
        val STABILITY_SECONDS = longPreferencesKey("stability_seconds")
        val RECHECK_SECONDS = longPreferencesKey("recheck_seconds")
    }
}

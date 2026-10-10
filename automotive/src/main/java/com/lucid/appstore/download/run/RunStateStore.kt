package com.lucid.appstore.download.run

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.lucid.appstore.download.model.BlockReason
import com.lucid.appstore.download.model.Progress
import com.lucid.appstore.download.model.RunPhase
import com.lucid.appstore.download.model.RunState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock

/** Persists [RunState] across reboot and process death (FR-24). */
interface RunStateStore {
    val state: Flow<RunState>
    suspend fun load(): RunState = state.first()
    suspend fun update(transform: (RunState) -> RunState): RunState
}

class DataStoreRunStateStore(context: Context, private val clock: Clock) : RunStateStore {
    private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("download_run_state") }

    override val state: Flow<RunState> = store.data.map { it.toRunState() }.distinctUntilChanged()

    override suspend fun update(transform: (RunState) -> RunState): RunState {
        var result = RunState()
        store.edit { prefs ->
            val old = prefs.toRunState()
            val new = transform(old)
            result = if (new.phase != old.phase || new.waitReason != old.waitReason) new.copy(lastStateChangeAt = clock.millis()) else new
            prefs.write(result)
        }
        return result
    }

    private fun Preferences.toRunState() = RunState(
        runId = this[RUN_ID] ?: "",
        phase = this[PHASE]?.let(RunPhase::valueOf) ?: RunPhase.IDLE,
        waitReason = this[WAIT_REASON]?.let(BlockReason::valueOf),
        hasStarted = this[HAS_STARTED] ?: false,
        progress = this[DOWNLOADED]?.let { Progress(it, this[TOTAL]) },
        lastStateChangeAt = this[LAST_CHANGE] ?: 0L,
        lastSuccessAt = this[LAST_SUCCESS],
    )

    private fun MutablePreferences.write(s: RunState) {
        this[RUN_ID] = s.runId
        this[PHASE] = s.phase.name
        s.waitReason?.let { this[WAIT_REASON] = it.name } ?: remove(WAIT_REASON)
        this[HAS_STARTED] = s.hasStarted
        s.progress?.let { p -> this[DOWNLOADED] = p.downloaded; p.total?.let { this[TOTAL] = it } ?: remove(TOTAL) }
            ?: run { remove(DOWNLOADED); remove(TOTAL) }
        this[LAST_CHANGE] = s.lastStateChangeAt
        s.lastSuccessAt?.let { this[LAST_SUCCESS] = it } ?: remove(LAST_SUCCESS)
    }

    private companion object {
        val RUN_ID = stringPreferencesKey("run_id")
        val PHASE = stringPreferencesKey("phase")
        val WAIT_REASON = stringPreferencesKey("wait_reason")
        val HAS_STARTED = booleanPreferencesKey("has_started")
        val DOWNLOADED = longPreferencesKey("downloaded")
        val TOTAL = longPreferencesKey("total")
        val LAST_CHANGE = longPreferencesKey("last_state_change_at")
        val LAST_SUCCESS = longPreferencesKey("last_success_at")
    }
}

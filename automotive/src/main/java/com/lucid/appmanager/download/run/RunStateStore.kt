package com.lucid.appmanager.download.run

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.lucid.appmanager.download.model.BlockReason
import com.lucid.appmanager.download.model.Progress
import com.lucid.appmanager.download.model.RunPhase
import com.lucid.appmanager.download.model.RunState
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
        manifestId = this[MANIFEST_ID],
        currentPackage = this[CURRENT_PACKAGE],
        apkPath = this[APK_PATH],
        progress = this[DOWNLOADED]?.let { Progress(it, this[TOTAL]) },
        lastStateChangeAt = this[LAST_CHANGE] ?: 0L,
        lastSuccessAt = this[LAST_SUCCESS],
    )

    /**
     * Every removal is a statement, never the right-hand side of an elvis.
     *
     * `MutablePreferences.remove` is typed to return the removed value, so using its result for a key
     * that is absent unboxes a null `Long` and throws. Writing a state whose `progress` is null — the
     * first update of any fresh run — is exactly that case.
     */
    private fun MutablePreferences.write(s: RunState) {
        this[RUN_ID] = s.runId
        this[PHASE] = s.phase.name
        this[HAS_STARTED] = s.hasStarted
        this[LAST_CHANGE] = s.lastStateChangeAt

        val reason = s.waitReason
        if (reason != null) this[WAIT_REASON] = reason.name else remove(WAIT_REASON)

        val manifestId = s.manifestId
        if (manifestId != null) this[MANIFEST_ID] = manifestId else remove(MANIFEST_ID)

        val currentPackage = s.currentPackage
        if (currentPackage != null) this[CURRENT_PACKAGE] = currentPackage else remove(CURRENT_PACKAGE)
        val apkPath = s.apkPath
        if (apkPath != null) this[APK_PATH] = apkPath else remove(APK_PATH)

        val progress = s.progress
        if (progress != null) {
            this[DOWNLOADED] = progress.downloaded
            val total = progress.total
            if (total != null) this[TOTAL] = total else remove(TOTAL)
        } else {
            remove(DOWNLOADED)
            remove(TOTAL)
        }

        val lastSuccess = s.lastSuccessAt
        if (lastSuccess != null) this[LAST_SUCCESS] = lastSuccess else remove(LAST_SUCCESS)
    }

    private companion object {
        val RUN_ID = stringPreferencesKey("run_id")
        val PHASE = stringPreferencesKey("phase")
        val WAIT_REASON = stringPreferencesKey("wait_reason")
        val HAS_STARTED = booleanPreferencesKey("has_started")
        val MANIFEST_ID = stringPreferencesKey("manifest_id")
        val CURRENT_PACKAGE = stringPreferencesKey("current_package")
        val APK_PATH = stringPreferencesKey("apk_path")
        val DOWNLOADED = longPreferencesKey("downloaded")
        val TOTAL = longPreferencesKey("total")
        val LAST_CHANGE = longPreferencesKey("last_state_change_at")
        val LAST_SUCCESS = longPreferencesKey("last_success_at")
    }
}

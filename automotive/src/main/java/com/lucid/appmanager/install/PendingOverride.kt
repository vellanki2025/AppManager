package com.lucid.appmanager.install

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.lucid.appmanager.catalogue.Digest
import kotlinx.coroutines.flow.first

/**
 * A verified APK staged for a partition override, waiting for the adb apply and a reboot (FR-48, FR-51).
 *
 * [bootCountAtStaging] is what makes the post-reboot check honest: without it the service cannot tell
 * "not rebooted yet" from "rebooted and it did not land", and would report failure on every process start.
 */
data class PendingOverride(
    val packageName: String,
    val targetVersionCode: Long,
    val stagedPath: String,
    val digest: Digest,
    val stagedAt: Long,
    val bootCountAtStaging: Int,
)

/** Survives process death and reboot; cleared only by the post-reboot check or an explicit action. */
class PendingOverrideStore(private val context: Context) {

    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE) }

    suspend fun load(): PendingOverride? {
        val prefs = store.data.first()
        val pkg = prefs[PACKAGE] ?: return null
        return PendingOverride(
            packageName = pkg,
            targetVersionCode = prefs[VERSION_CODE] ?: return null,
            stagedPath = prefs[PATH] ?: return null,
            digest = Digest(prefs[DIGEST_ALGO] ?: Digest.SHA_256, prefs[DIGEST_VALUE].orEmpty()),
            stagedAt = prefs[STAGED_AT] ?: 0L,
            bootCountAtStaging = (prefs[BOOT_COUNT] ?: 0L).toInt(),
        )
    }

    suspend fun save(override: PendingOverride) {
        store.edit {
            it[PACKAGE] = override.packageName
            it[VERSION_CODE] = override.targetVersionCode
            it[PATH] = override.stagedPath
            it[DIGEST_ALGO] = override.digest.algorithm
            it[DIGEST_VALUE] = override.digest.value
            it[STAGED_AT] = override.stagedAt
            it[BOOT_COUNT] = override.bootCountAtStaging.toLong()
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        private const val FILE = "pending_override"
        private val PACKAGE = stringPreferencesKey("package")
        private val VERSION_CODE = longPreferencesKey("version_code")
        private val PATH = stringPreferencesKey("path")
        private val DIGEST_ALGO = stringPreferencesKey("digest_algo")
        private val DIGEST_VALUE = stringPreferencesKey("digest_value")
        private val STAGED_AT = longPreferencesKey("staged_at")
        private val BOOT_COUNT = longPreferencesKey("boot_count")

        /**
         * Monotonic boot counter. Returns 0 where the platform does not expose it, which degrades the
         * post-reboot check to "report once after staging" rather than breaking it (design §15.2).
         */
        fun bootCount(context: Context): Int =
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    }
}

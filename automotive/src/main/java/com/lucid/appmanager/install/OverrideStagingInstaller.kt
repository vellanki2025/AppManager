package com.lucid.appmanager.install

import android.content.Context
import com.lucid.appmanager.catalogue.Manifest
import com.lucid.appmanager.catalogue.ManifestChecker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of the post-reboot check on a staged override (FR-50). */
sealed interface OverrideCheck {
    data object NoneStaged : OverrideCheck
    data class Applied(val packageName: String, val versionCode: Long) : OverrideCheck

    /** Rebooted since staging, but the installed version did not change. The record is kept. */
    data class NotApplied(val override: PendingOverride, val installedVersionCode: Long?) : OverrideCheck

    /** Staged, but no reboot has happened yet. Nothing to report. */
    data class AwaitingReboot(val override: PendingOverride) : OverrideCheck
}

/**
 * Install path B (FR-48 … FR-51): stage a verified APK for a partition override.
 *
 * This installer deliberately installs nothing. The app cannot remount a system partition and must not
 * reboot the vehicle (A14, A15), so it copies the APK somewhere stable, records what it is waiting for,
 * and prints the adb sequence a human or a test script runs. Whether it worked is answered after the next
 * reboot by [checkApplied], not here.
 *
 * Selecting this mode on a build with a read-only partition is not blocked, because the app cannot
 * reliably detect that (FR-53). Staging succeeds and the post-reboot check reports it did not land.
 */
class OverrideStagingInstaller(
    private val context: Context,
    private val store: PendingOverrideStore,
    private val checker: ManifestChecker,
    /** Partition directory the printed adb sequence targets (C7). */
    private val targetPartitionDir: String = DEFAULT_PARTITION_DIR,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Installer {

    override val mode = InstallMode.APK_OVERRIDE

    override suspend fun install(manifest: Manifest, apk: File): InstallOutcome = withContext(ioDispatcher) {
        if (!apk.exists() || apk.length() == 0L) {
            return@withContext InstallOutcome.Failed(-1, "APK missing or empty at ${apk.absolutePath}")
        }

        val staged = stagedFile(manifest.packageName)
        staged.parentFile?.mkdirs()
        staged.delete()
        try {
            apk.copyTo(staged, overwrite = true)
        } catch (e: Exception) {
            return@withContext InstallOutcome.Failed(-1, "could not stage: ${e.message}")
        }

        store.save(
            PendingOverride(
                packageName = manifest.packageName,
                targetVersionCode = manifest.versionCode,
                stagedPath = staged.absolutePath,
                digest = manifest.digest,
                stagedAt = System.currentTimeMillis(),
                bootCountAtStaging = PendingOverrideStore.bootCount(context),
            ),
        )
        apk.delete()
        InstallOutcome.Staged
    }

    fun stagedFile(packageName: String) = File(File(context.filesDir, OVERRIDE_DIR), "$packageName.apk")

    /**
     * Reads the staged record against what is installed now (FR-50).
     *
     * Run at service start rather than inside a run: the reboot may be days later with no run in flight.
     */
    suspend fun checkApplied(): OverrideCheck {
        val pending = store.load() ?: return OverrideCheck.NoneStaged
        val installed = checker.installedVersionCode(pending.packageName)

        if (installed != null && installed >= pending.targetVersionCode) {
            store.clear()
            File(pending.stagedPath).delete()
            return OverrideCheck.Applied(pending.packageName, installed)
        }

        val rebooted = PendingOverrideStore.bootCount(context) > pending.bootCountAtStaging
        return if (rebooted) OverrideCheck.NotApplied(pending, installed) else OverrideCheck.AwaitingReboot(pending)
    }

    suspend fun clear() {
        store.load()?.let { File(it.stagedPath).delete() }
        store.clear()
    }

    /**
     * The adb sequence that applies a staged override (FR-49).
     *
     * Emitted rather than executed. The app has no way to remount a partition and no business rebooting
     * a vehicle; a human or a test script runs this.
     */
    fun applyCommands(override: PendingOverride): String {
        val appDir = override.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        val target = "$targetPartitionDir/$appDir/$appDir.apk"
        return buildString {
            appendLine("# apply the staged override for ${override.packageName} (v${override.targetVersionCode})")
            appendLine("adb root && adb remount")
            appendLine("adb push ${override.stagedPath} $target")
            appendLine("adb shell chmod 644 $target")
            append("adb reboot")
        }
    }

    private companion object {
        const val OVERRIDE_DIR = "override"
        const val DEFAULT_PARTITION_DIR = "/product/priv-app"
    }
}

package com.lucid.appmanager.install

import com.lucid.appmanager.catalogue.Artifact
import java.io.File

/** Which install path the service uses (FR-52). Chosen by feature flag, read at install time. */
enum class InstallMode {
    /** In-process [android.content.pm.PackageInstaller] session. The default; works on any build. */
    PACKAGE_MANAGER,

    /**
     * Stage the APK for a partition override applied out of band.
     *
     * The app never writes to /system or /product and never reboots (A14, A15); it stages and reports.
     * Requires a writable partition, so this is a userdebug-only path (A16).
     */
    APK_OVERRIDE,
    ;

    companion object {
        fun parse(value: String?): InstallMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PACKAGE_MANAGER
    }
}

sealed interface InstallOutcome {
    /** The package manager reports it installed. [com.lucid.appmanager.catalogue.ArtifactChecker] confirms. */
    data object Installed : InstallOutcome

    /** APK_OVERRIDE: staged and recorded, waiting for the adb apply and the next reboot. */
    data object Staged : InstallOutcome

    /**
     * The platform wants a confirmation dialog (FR-46).
     *
     * Reported rather than shown: this runs on a head unit with nobody necessarily in front of it, and a
     * silent install that quietly becomes a modal dialog is worse than an honest failure.
     */
    data object NeedsUserAction : InstallOutcome

    data class Failed(val code: Int, val message: String) : InstallOutcome
}

interface Installer {
    val mode: InstallMode

    /** @param apk a file that has already passed [com.lucid.appmanager.download.verify.IntegrityVerifier]. */
    suspend fun install(artifact: Artifact, apk: File): InstallOutcome
}

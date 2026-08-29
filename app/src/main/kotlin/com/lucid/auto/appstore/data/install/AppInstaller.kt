package com.lucid.auto.appstore.data.install

import java.io.File

/**
 * How this build is able to install packages. Decided at runtime, not at compile time, so one
 * APK works both as a sideloaded app and as a privileged system app.
 */
enum class InstallMode {
    /**
     * `INSTALL_PACKAGES` is granted — the app is in /system/priv-app and allowlisted.
     * Installs and updates complete with no user dialog at all.
     */
    SILENT,

    /**
     * Ordinary app. Install of a *new* package shows the platform confirmation dialog
     * (`REQUEST_INSTALL_PACKAGES`). On API 31+, updates to packages this store itself installed
     * can still complete silently via `UPDATE_PACKAGES_WITHOUT_USER_ACTION`.
     */
    INTERACTIVE,
}

sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data object Cancelled : InstallOutcome
    data class Failure(val statusCode: Int, val message: String) : InstallOutcome
    /** The system dialog was shown; the true result arrives after the user answers. */
    data object PendingUserAction : InstallOutcome
}

interface AppInstaller {
    val installMode: InstallMode

    /**
     * Installs or updates [packageName] from [apk].
     *
     * Suspends until the platform reports an outcome. The caller is expected to re-read local
     * versions afterwards rather than trusting the outcome alone (FR-5.6).
     */
    suspend fun install(packageName: String, apk: File): InstallOutcome
}

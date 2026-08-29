package com.lucid.auto.appstore.domain

import androidx.compose.ui.graphics.ImageBitmap

/**
 * One row in the list: icon, title, version, state.
 *
 * The version line doubles as a short status line while work is in flight — `Downloading… 40%`,
 * `Installing…`, `Failed`. That is the only prose a row may show, and it is a fixed vocabulary:
 * there is no field here for an error message or any other free text, so a failure reason cannot
 * reach a row. Reasons are reported above the list instead.
 */
data class AppItem(
    val packageName: String,
    val title: String,
    val icon: ImageBitmap?,
    val installedVersionName: String?,
    val installedVersionCode: Long?,
    val availableVersionName: String?,
    val availableVersionCode: Long?,
    val state: AppState,
    /** Transient work status, or null when idle. */
    val progress: InstallProgress? = null,
    val releaseNotes: String? = null,
) {
    /** A download or install is actively running for this package. */
    val isBusy: Boolean
        get() = progress?.isBusy == true

    /** The last attempt failed and the row is offering another try. */
    val hasFailed: Boolean
        get() = progress is InstallProgress.Failed

    /**
     * The line under the title.
     *
     * Idle, it is version numbers only:
     *   INSTALL   -> "1.2.0"
     *   UPDATE    -> "1.1.0 → 1.2.0"
     *   INSTALLED -> "1.2.0"
     *
     * Working, it is one of exactly three fixed strings:
     *   "Downloading… 40%" · "Installing…" · "Failed"
     */
    val versionLabel: String
        get() = when (val work = progress) {
            is InstallProgress.Downloading -> "Downloading… ${work.percent}%"
            InstallProgress.Installing -> "Installing…"
            InstallProgress.Failed -> "Failed"
            null -> versionOnlyLabel
        }

    /** The version text with no status overlay — what the row shows once work settles. */
    val versionOnlyLabel: String
        get() = when (state) {
            AppState.INSTALL ->
                availableVersionName ?: UNKNOWN_VERSION

            AppState.UPDATE ->
                "${installedVersionName ?: UNKNOWN_VERSION} → " +
                    (availableVersionName ?: UNKNOWN_VERSION)

            AppState.INSTALLED ->
                installedVersionName ?: availableVersionName ?: UNKNOWN_VERSION
        }

    /** The action control's label. Always one of the three product states. */
    val actionLabel: String
        get() = when (state) {
            AppState.INSTALL -> "Install"
            AppState.UPDATE -> "Update"
            AppState.INSTALLED -> "Installed"
        }

    /**
     * Whether tapping this row's control should do anything right now.
     *
     * A failed row stays actionable — tapping it retries.
     */
    val isActionable: Boolean
        get() = state.isActionable && !isBusy

    private companion object {
        const val UNKNOWN_VERSION = "—"
    }
}

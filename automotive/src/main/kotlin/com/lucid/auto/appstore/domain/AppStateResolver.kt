package com.lucid.auto.appstore.domain

import com.lucid.auto.appstore.data.catalog.RemoteApp
import com.lucid.auto.appstore.data.local.InstalledApp

/**
 * Pure state derivation: (what is on the device, what the server offers) -> [AppState].
 *
 * Deliberately free of Android imports so it runs on a plain JVM in unit tests. This is the
 * single rule that decides whether a row says Install, Update or Installed.
 *
 * The comparison uses `versionCode` (a monotonically increasing Long owned by the build system)
 * and never `versionName`. `versionName` is a free-form marketing string: comparing it as text
 * makes "1.10.0" sort *below* "1.9.0", which silently breaks updates.
 */
object AppStateResolver {

    fun resolve(local: InstalledApp?, remote: RemoteApp?): AppState = when {
        // Nothing on the device, nothing on the server: caller should not list this at all.
        local == null && remote == null -> AppState.INSTALLED

        // Not on the device, but the catalogue offers it.
        local == null -> AppState.INSTALL

        // On the device but absent from the catalogue: nothing to update to.
        remote == null -> AppState.INSTALLED

        // Strictly greater means an update is available. Equal or lower means we are current;
        // a lower remote version is treated as current rather than offering a downgrade,
        // because PackageInstaller rejects downgrades for non-debuggable apps anyway.
        remote.versionCode > local.versionCode -> AppState.UPDATE

        else -> AppState.INSTALLED
    }

    /** True when the row should be listed at all (FR-3.1: omit when neither side knows it). */
    fun isListable(local: InstalledApp?, remote: RemoteApp?): Boolean =
        local != null || remote != null
}

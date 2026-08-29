package com.lucid.auto.appstore.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A row may show only four things: icon, title, version, state.
 *
 * These tests pin the two text fields to that contract — the version line carries version numbers
 * and nothing else, and the action label is always one of the three product states.
 */
class AppItemTest {

    private fun item(
        state: AppState,
        installed: String? = "1.1.0",
        available: String? = "1.2.0",
        progress: InstallProgress? = null,
    ) = AppItem(
        packageName = "com.lucid.auto.media",
        title = "Lucid Media",
        icon = null,
        installedVersionName = installed,
        installedVersionCode = installed?.let { 10100 },
        availableVersionName = available,
        availableVersionCode = available?.let { 10200 },
        state = state,
        progress = progress,
    )

    @Test
    fun `install shows the version that would be installed`() {
        assertThat(item(AppState.INSTALL, installed = null).versionLabel).isEqualTo("1.2.0")
    }

    @Test
    fun `update shows current then target`() {
        assertThat(item(AppState.UPDATE).versionLabel).isEqualTo("1.1.0 → 1.2.0")
    }

    @Test
    fun `installed shows the installed version only`() {
        assertThat(item(AppState.INSTALLED, installed = "1.2.0").versionLabel).isEqualTo("1.2.0")
    }

    @Test
    fun `a missing version falls back to a dash, never to prose`() {
        val label = item(AppState.INSTALLED, installed = null, available = null).versionLabel
        assertThat(label).isEqualTo("—")
    }

    @Test
    fun `downloading shows percent progress`() {
        assertThat(item(AppState.UPDATE, progress = InstallProgress.Downloading(40)).versionLabel)
            .isEqualTo("Downloading… 40%")
    }

    @Test
    fun `installing shows a fixed label`() {
        assertThat(item(AppState.UPDATE, progress = InstallProgress.Installing).versionLabel)
            .isEqualTo("Installing…")
    }

    @Test
    fun `a failure shows the bare word, never the reason`() {
        val failed = item(AppState.UPDATE, progress = InstallProgress.Failed)

        assertThat(failed.versionLabel).isEqualTo("Failed")
        assertThat(failed.hasFailed).isTrue()
    }

    @Test
    fun `the status vocabulary is fixed - no free text can reach a row`() {
        // Every representable row, across states and every progress case.
        val everyProgress = listOf(
            null,
            InstallProgress.Downloading(0),
            InstallProgress.Downloading(73),
            InstallProgress.Installing,
            InstallProgress.Failed,
        )
        val everyRow = AppState.entries.flatMap { state ->
            everyProgress.flatMap { p ->
                listOf(
                    item(state, progress = p),
                    item(state, installed = null, progress = p),
                    item(state, available = null, progress = p),
                    item(state, installed = null, available = null, progress = p),
                )
            }
        }

        val allowedStatus = Regex("^(Downloading… \\d{1,3}%|Installing…|Failed)$")

        for (row in everyRow) {
            val label = row.versionLabel
            if (row.progress != null) {
                // Working: exactly one of the three fixed strings.
                assertThat(allowedStatus.matches(label)).isTrue()
            } else {
                // Idle: version numbers only — no status or error prose.
                for (word in listOf("Downloading", "Installing", "Failed", "failed",
                        "available", "Not installed", "error", "Error", "Could not", "HTTP")) {
                    assertThat(label).doesNotContain(word)
                }
            }
        }
    }

    @Test
    fun `a failed row still knows its underlying version and state`() {
        val failed = item(AppState.UPDATE, progress = InstallProgress.Failed)

        assertThat(failed.versionOnlyLabel).isEqualTo("1.1.0 → 1.2.0")
        assertThat(failed.state).isEqualTo(AppState.UPDATE)
        assertThat(failed.actionLabel).isEqualTo("Update")
        // Tapping retries.
        assertThat(failed.isActionable).isTrue()
    }

    @Test
    fun `the action label is always one of the three product states`() {
        val allowed = setOf("Install", "Update", "Installed")

        for (state in AppState.entries) {
            assertThat(item(state).actionLabel).isIn(allowed)
            // Neither busy nor failed may rename the control.
            assertThat(item(state, progress = InstallProgress.Downloading(5)).actionLabel)
                .isIn(allowed)
            assertThat(item(state, progress = InstallProgress.Installing).actionLabel).isIn(allowed)
            assertThat(item(state, progress = InstallProgress.Failed).actionLabel).isIn(allowed)
        }

        assertThat(item(AppState.INSTALL).actionLabel).isEqualTo("Install")
        assertThat(item(AppState.UPDATE).actionLabel).isEqualTo("Update")
        assertThat(item(AppState.INSTALLED).actionLabel).isEqualTo("Installed")
    }

    @Test
    fun `a busy row keeps its state label but stops being actionable`() {
        val busy = item(AppState.UPDATE, progress = InstallProgress.Downloading(40))

        assertThat(busy.actionLabel).isEqualTo("Update")
        assertThat(busy.versionOnlyLabel).isEqualTo("1.1.0 → 1.2.0")
        assertThat(busy.isBusy).isTrue()
        assertThat(busy.isActionable).isFalse()
    }

    @Test
    fun `installed is never actionable, busy or not`() {
        assertThat(item(AppState.INSTALLED).isActionable).isFalse()
        assertThat(
            item(AppState.INSTALLED, progress = InstallProgress.Downloading(10)).isActionable,
        ).isFalse()
    }

    @Test
    fun `idle install and update rows are actionable`() {
        assertThat(item(AppState.INSTALL).isActionable).isTrue()
        assertThat(item(AppState.UPDATE).isActionable).isTrue()
    }
}

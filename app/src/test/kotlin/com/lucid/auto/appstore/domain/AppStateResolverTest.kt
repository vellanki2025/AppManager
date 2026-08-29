package com.lucid.auto.appstore.domain

import com.google.common.truth.Truth.assertThat
import com.lucid.auto.appstore.data.local.InstalledApp
import com.lucid.auto.appstore.fakes.remoteApp
import org.junit.Test

/**
 * The single most important rule in the app: Install vs Update vs Installed.
 * Covers the full truth table in requirement FR-3.1.
 */
class AppStateResolverTest {

    private fun installed(versionName: String, versionCode: Long) =
        InstalledApp("com.lucid.auto.media", "Lucid Media", versionName, versionCode)

    @Test
    fun `not installed but in catalog resolves to INSTALL`() {
        val state = AppStateResolver.resolve(
            local = null,
            remote = remoteApp("com.spotify.music", "8.9.60", 89060000),
        )
        assertThat(state).isEqualTo(AppState.INSTALL)
    }

    @Test
    fun `installed with a newer version available resolves to UPDATE`() {
        val state = AppStateResolver.resolve(
            local = installed("1.1.0", 10100),
            remote = remoteApp("com.lucid.auto.media", "1.2.0", 10200),
        )
        assertThat(state).isEqualTo(AppState.UPDATE)
    }

    @Test
    fun `installed at the same version resolves to INSTALLED`() {
        val state = AppStateResolver.resolve(
            local = installed("2.0.0", 20000),
            remote = remoteApp("com.lucid.auto.radio", "2.0.0", 20000),
        )
        assertThat(state).isEqualTo(AppState.INSTALLED)
    }

    @Test
    fun `installed ahead of the catalog resolves to INSTALLED, never a downgrade`() {
        val state = AppStateResolver.resolve(
            local = installed("1.3.0", 10300),
            remote = remoteApp("com.lucid.auto.media", "1.2.0", 10200),
        )
        assertThat(state).isEqualTo(AppState.INSTALLED)
    }

    @Test
    fun `installed but missing from the catalog resolves to INSTALLED`() {
        val state = AppStateResolver.resolve(local = installed("1.0.0", 10000), remote = null)
        assertThat(state).isEqualTo(AppState.INSTALLED)
    }

    @Test
    fun `version comparison uses versionCode, not the versionName string`() {
        // "1.10.0" sorts BELOW "1.9.0" as text. Only versionCode gets this right.
        val local = installed("1.9.0", 10900)
        val remote = remoteApp("com.lucid.auto.media", "1.10.0", 11000)

        assertThat(remote.versionName < local.versionName).isTrue()          // the trap
        assertThat(AppStateResolver.resolve(local, remote)).isEqualTo(AppState.UPDATE)
    }

    @Test
    fun `row is only listable when at least one side knows the app`() {
        assertThat(AppStateResolver.isListable(null, null)).isFalse()
        assertThat(AppStateResolver.isListable(installed("1.0.0", 1), null)).isTrue()
        assertThat(
            AppStateResolver.isListable(null, remoteApp("com.spotify.music", "1", 1)),
        ).isTrue()
    }

    @Test
    fun `only INSTALL and UPDATE are actionable`() {
        assertThat(AppState.INSTALL.isActionable).isTrue()
        assertThat(AppState.UPDATE.isActionable).isTrue()

        // "tapping on installed will not do anything"
        assertThat(AppState.INSTALLED.isActionable).isFalse()
    }

    @Test
    fun `there are exactly three states, so a row cannot render a fourth`() {
        assertThat(AppState.entries).containsExactly(
            AppState.INSTALL,
            AppState.UPDATE,
            AppState.INSTALLED,
        )
    }
}

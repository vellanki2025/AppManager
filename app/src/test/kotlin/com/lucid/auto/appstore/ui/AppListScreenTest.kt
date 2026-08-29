package com.lucid.auto.appstore.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.lucid.auto.appstore.data.install.InstallMode
import com.lucid.auto.appstore.domain.AppItem
import com.lucid.auto.appstore.domain.AppState
import com.lucid.auto.appstore.domain.InstallProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests driven by Robolectric, so they run under `./gradlew test` with no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val media = AppItem(
        packageName = "com.lucid.auto.media",
        title = "Lucid Media",
        icon = null,
        installedVersionName = "1.1.0",
        installedVersionCode = 10100,
        availableVersionName = "1.2.0",
        availableVersionCode = 10200,
        state = AppState.UPDATE,
    )
    private val radio = AppItem(
        packageName = "com.lucid.auto.radio",
        title = "Lucid Radio",
        icon = null,
        installedVersionName = "2.0.0",
        installedVersionCode = 20000,
        availableVersionName = "2.0.0",
        availableVersionCode = 20000,
        state = AppState.INSTALLED,
    )
    private val spotify = AppItem(
        packageName = "com.spotify.music",
        title = "Spotify",
        icon = null,
        installedVersionName = null,
        installedVersionCode = null,
        availableVersionName = "8.9.60",
        availableVersionCode = 89060000,
        state = AppState.INSTALL,
    )

    private val taps = mutableListOf<String>()
    private val autoUpdateChanges = mutableListOf<Boolean>()
    private var refreshCount = 0

    private fun render(
        items: List<AppItem> = listOf(media, radio, spotify),
        autoUpdate: Boolean = false,
        error: String? = null,
        refreshing: Boolean = false,
        installMode: InstallMode = InstallMode.SILENT,
    ) {
        compose.setContent {
            AppListScreen(
                state = AppListUiState(
                    items = items,
                    isRefreshing = refreshing,
                    errorMessage = error,
                    autoUpdateEnabled = autoUpdate,
                    installMode = installMode,
                ),
                onAppAction = { taps += it },
                onRefresh = { refreshCount++ },
                onAutoUpdateChanged = { autoUpdateChanges += it },
            )
        }
    }

    @Test
    fun `every managed app is listed with its title and version line`() {
        render()

        compose.onNodeWithTag(AppRowTags.row("com.lucid.auto.media")).assertIsDisplayed()
        compose.onNodeWithTag(AppRowTags.row("com.lucid.auto.radio")).assertIsDisplayed()
        compose.onNodeWithTag(AppRowTags.row("com.spotify.music")).assertIsDisplayed()

        compose.onNodeWithTag(AppRowTags.title("com.lucid.auto.media"))
            .assertTextEquals("Lucid Media")
        compose.onNodeWithTag(AppRowTags.version("com.lucid.auto.media"))
            .assertTextEquals("1.1.0 → 1.2.0")
        compose.onNodeWithTag(AppRowTags.version("com.lucid.auto.radio"))
            .assertTextEquals("2.0.0")
        compose.onNodeWithTag(AppRowTags.version("com.spotify.music"))
            .assertTextEquals("8.9.60")
    }

    @Test
    fun `the action control label matches the state`() {
        render()

        compose.onNodeWithTag(AppRowTags.action("com.lucid.auto.media"))
            .assertTextEquals("Update")
        compose.onNodeWithTag(AppRowTags.action("com.lucid.auto.radio"))
            .assertTextEquals("Installed")
        compose.onNodeWithTag(AppRowTags.action("com.spotify.music"))
            .assertTextEquals("Install")
    }

    @Test
    fun `tapping install emits the package`() {
        render()

        compose.onNodeWithTag(AppRowTags.action("com.spotify.music"))
            .assertIsEnabled()
            .performClick()

        assertThat(taps).containsExactly("com.spotify.music")
    }

    @Test
    fun `tapping update emits the package`() {
        render()

        compose.onNodeWithTag(AppRowTags.action("com.lucid.auto.media")).performClick()

        assertThat(taps).containsExactly("com.lucid.auto.media")
    }

    @Test
    fun `the installed control is disabled and tapping it does nothing`() {
        render()

        val installedButton = compose.onNodeWithTag(AppRowTags.action("com.lucid.auto.radio"))
        installedButton.assertIsNotEnabled()
        installedButton.performClick()

        assertThat(taps).isEmpty()
    }

    @Test
    fun `a downloading row shows progress and is not tappable`() {
        val downloading = media.copy(progress = InstallProgress.Downloading(40))
        val installing = spotify.copy(progress = InstallProgress.Installing)
        render(items = listOf(downloading, installing))

        compose.onNodeWithTag(AppRowTags.version("com.lucid.auto.media"))
            .assertTextEquals("Downloading… 40%")
        compose.onNodeWithTag(AppRowTags.version("com.spotify.music"))
            .assertTextEquals("Installing…")

        // The control keeps its state label and goes inert.
        compose.onNodeWithTag(AppRowTags.action("com.lucid.auto.media"))
            .assertTextEquals("Update")
            .assertIsNotEnabled()
        compose.onNodeWithTag(AppRowTags.action("com.spotify.music"))
            .assertTextEquals("Install")
            .assertIsNotEnabled()

        compose.onNodeWithTag(AppRowTags.action("com.lucid.auto.media")).performClick()
        assertThat(taps).isEmpty()
    }

    @Test
    fun `a failed row shows the bare word Failed and retries on tap`() {
        render(items = listOf(media.copy(progress = InstallProgress.Failed)))

        compose.onNodeWithTag(AppRowTags.version("com.lucid.auto.media"))
            .assertTextEquals("Failed")
        compose.onNodeWithTag(AppRowTags.action("com.lucid.auto.media"))
            .assertTextEquals("Update")
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()

        assertThat(taps).containsExactly("com.lucid.auto.media")
    }

    @Test
    fun `the failure reason appears above the list, never inside a row`() {
        render(
            items = listOf(media.copy(progress = InstallProgress.Failed), radio, spotify),
            error = "Lucid Media could not be installed: Download failed: HTTP 404",
        )

        compose.onNodeWithTag(AppRowTags.ERROR_BANNER).assertIsDisplayed()

        // The row says only "Failed" — the HTTP 404 detail stays in the banner.
        compose.onNodeWithTag(AppRowTags.version("com.lucid.auto.media"))
            .assertTextEquals("Failed")
        compose.onNodeWithTag(AppRowTags.version("com.lucid.auto.radio"))
            .assertTextEquals("2.0.0")
        compose.onNodeWithTag(AppRowTags.version("com.spotify.music"))
            .assertTextEquals("8.9.60")
    }

    @Test
    fun `the auto-update switch reflects and reports the flag`() {
        render(autoUpdate = false)

        compose.onNodeWithTag(AppRowTags.AUTO_UPDATE_SWITCH).assertIsOff().performClick()

        assertThat(autoUpdateChanges).containsExactly(true)
    }

    @Test
    fun `the auto-update switch renders as on when the flag is enabled`() {
        render(autoUpdate = true)

        compose.onNodeWithTag(AppRowTags.AUTO_UPDATE_SWITCH).assertIsOn()
    }

    @Test
    fun `the error banner appears and retry refreshes`() {
        render(error = "Could not reach the app catalogue: connection refused")

        compose.onNodeWithTag(AppRowTags.ERROR_BANNER).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        assertThat(refreshCount).isEqualTo(1)
    }

    @Test
    fun `the refresh action refreshes`() {
        render()

        compose.onNodeWithTag(AppRowTags.REFRESH_BUTTON).performClick()

        assertThat(refreshCount).isEqualTo(1)
    }

    @Test
    fun `an empty catalog shows the empty state`() {
        render(items = emptyList())

        compose.onNodeWithText("No apps to show yet.").assertIsDisplayed()
    }
}

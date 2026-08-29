package com.lucid.auto.appstore.ui

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lucid.auto.appstore.data.catalog.AppCatalog
import com.lucid.auto.appstore.data.catalog.CatalogRepository
import com.lucid.auto.appstore.domain.AppRepository
import com.lucid.auto.appstore.domain.AppState
import com.lucid.auto.appstore.domain.UpdateCoordinator
import com.lucid.auto.appstore.fakes.FakeApkDownloader
import com.lucid.auto.appstore.fakes.FakeAppInstaller
import com.lucid.auto.appstore.fakes.FakeAutoUpdateScheduler
import com.lucid.auto.appstore.fakes.FakeCatalogApi
import com.lucid.auto.appstore.fakes.FakePackageInspector
import com.lucid.auto.appstore.fakes.FakeRemoteIconLoader
import com.lucid.auto.appstore.fakes.FakeSettingsRepository
import com.lucid.auto.appstore.fakes.remoteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppListViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    private val inspector = FakePackageInspector()
    private val api = FakeCatalogApi()
    private val installer = FakeAppInstaller()
    private val scheduler = FakeAutoUpdateScheduler()
    private val iconLoader = FakeRemoteIconLoader()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var appRepository: AppRepository
    private lateinit var coordinator: UpdateCoordinator

    private val catalog = AppCatalog(
        apps = listOf(
            remoteApp("com.lucid.auto.media", "1.2.0", 10200, title = "Lucid Media"),
            remoteApp("com.lucid.auto.radio", "2.0.0", 20000, title = "Lucid Radio"),
            remoteApp("com.spotify.music", "8.9.60", 89060000, title = "Spotify"),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api.catalog = catalog
        catalogRepository = CatalogRepository(api)
        appRepository = AppRepository(
            inspector,
            catalogRepository,
            "com.lucid.auto.appstore",
            UnconfinedTestDispatcher(),
        )
        coordinator = UpdateCoordinator(
            appRepository, FakeApkDownloader(temp.root), installer, "com.lucid.auto.appstore",
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(settings: FakeSettingsRepository = FakeSettingsRepository()) =
        AppListViewModel(
            appRepository = appRepository,
            catalogRepository = catalogRepository,
            updateCoordinator = coordinator,
            settingsRepository = settings,
            autoUpdateScheduler = scheduler,
            remoteIconLoader = iconLoader,
        )

    @Test
    fun `on launch the list shows the three resolved states`() = runTest(dispatcher) {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        inspector.put("com.lucid.auto.radio", "2.0.0", 20000)

        val vm = viewModel()
        advanceUntilIdle()

        val items = vm.uiState.value.items.associateBy { it.packageName }
        assertThat(items.getValue("com.lucid.auto.media").state).isEqualTo(AppState.UPDATE)
        assertThat(items.getValue("com.lucid.auto.radio").state).isEqualTo(AppState.INSTALLED)
        assertThat(items.getValue("com.spotify.music").state).isEqualTo(AppState.INSTALL)
        assertThat(vm.uiState.value.isRefreshing).isFalse()
        assertThat(vm.uiState.value.errorMessage).isNull()
    }

    @Test
    fun `a catalog failure on a cold start shows the banner and an empty list`() =
        runTest(dispatcher) {
            // The device has the app, but the list is catalogue-driven and nothing has been
            // fetched yet, so there is nothing to show.
            inspector.put("com.lucid.auto.media", "1.1.0", 10100)
            api.error = java.io.IOException("connection refused")

            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.uiState.value.errorMessage).contains("Could not reach the app catalogue")
            assertThat(vm.uiState.value.items).isEmpty()
        }

    @Test
    fun `a catalog failure after a good fetch keeps the previous list on screen`() =
        runTest(dispatcher) {
            inspector.put("com.lucid.auto.media", "1.1.0", 10100)
            val vm = viewModel()
            advanceUntilIdle()
            assertThat(vm.uiState.value.items).hasSize(3)

            api.error = java.io.IOException("connection refused")
            vm.refresh()
            advanceUntilIdle()

            // The cached catalogue still drives the list, so the screen stays usable.
            assertThat(vm.uiState.value.errorMessage).contains("Could not reach the app catalogue")
            assertThat(vm.uiState.value.items).hasSize(3)
        }

    @Test
    fun `the error banner names the url that was actually tried`() = runTest(dispatcher) {
        api.error = java.io.IOException("Failed to connect to /10.0.2.2:8080")

        val vm = viewModel(FakeSettingsRepository(baseUrl = "http://10.0.2.2:8080"))
        advanceUntilIdle()

        // A bare "could not connect" cannot distinguish a down server from a wrong port.
        val message = vm.uiState.value.errorMessage
        assertThat(message).contains("http://10.0.2.2:8080")
        assertThat(message).contains("Failed to connect")
    }

    @Test
    fun `the error banner reflects a changed base url`() = runTest(dispatcher) {
        api.error = java.io.IOException("connection refused")

        val vm = viewModel(FakeSettingsRepository(baseUrl = "http://192.168.1.20:8081"))
        advanceUntilIdle()

        assertThat(vm.uiState.value.errorMessage).contains("http://192.168.1.20:8081")
    }

    @Test
    fun `tapping install triggers a download and install`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAppAction("com.spotify.music")
        advanceUntilIdle()

        assertThat(installer.installedPackages).containsExactly("com.spotify.music")
    }

    @Test
    fun `tapping update triggers an install of the newer version`() = runTest(dispatcher) {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAppAction("com.lucid.auto.media")
        advanceUntilIdle()

        assertThat(installer.installedPackages).containsExactly("com.lucid.auto.media")
    }

    @Test
    fun `tapping an INSTALLED row does nothing`() = runTest(dispatcher) {
        inspector.put("com.lucid.auto.radio", "2.0.0", 20000)
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(
            vm.uiState.value.items.first { it.packageName == "com.lucid.auto.radio" }.state,
        ).isEqualTo(AppState.INSTALLED)

        vm.onAppAction("com.lucid.auto.radio")
        advanceUntilIdle()

        assertThat(installer.installedPackages).isEmpty()
    }

    @Test
    fun `auto-update OFF does not update anything on launch`() = runTest(dispatcher) {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)   // an update is pending

        viewModel(FakeSettingsRepository(autoUpdate = false))
        advanceUntilIdle()

        assertThat(installer.installedPackages).isEmpty()
    }

    @Test
    fun `auto-update ON applies pending updates on launch without any tap`() =
        runTest(dispatcher) {
            inspector.put("com.lucid.auto.media", "1.1.0", 10100)   // UPDATE
            inspector.put("com.lucid.auto.radio", "2.0.0", 20000)   // INSTALLED
            installer.onSuccess = { pkg -> inspector.put(pkg, "1.2.0", 10200) }

            viewModel(FakeSettingsRepository(autoUpdate = true))
            advanceUntilIdle()

            assertThat(installer.installedPackages).containsExactly("com.lucid.auto.media")
        }

    @Test
    fun `auto-update ON never installs an app that is not on the device`() = runTest(dispatcher) {
        // Spotify is in the catalog but not installed.
        viewModel(FakeSettingsRepository(autoUpdate = true))
        advanceUntilIdle()

        assertThat(installer.installedPackages).doesNotContain("com.spotify.music")
    }

    @Test
    fun `enabling the flag enqueues the worker and disabling cancels it`() = runTest(dispatcher) {
        val settings = FakeSettingsRepository(autoUpdate = false)
        val vm = viewModel(settings)
        advanceUntilIdle()

        vm.setAutoUpdateEnabled(true)
        advanceUntilIdle()
        assertThat(scheduler.enqueueCount).isEqualTo(1)

        vm.setAutoUpdateEnabled(false)
        advanceUntilIdle()
        assertThat(scheduler.cancelCount).isEqualTo(1)
        assertThat(scheduler.calls).containsExactly("enqueue", "cancel").inOrder()
    }

    @Test
    fun `enabling the flag refetches the catalog before applying updates`() = runTest(dispatcher) {
        // The device is current against the catalogue the app fetched at launch.
        inspector.put("com.lucid.auto.media", "1.2.0", 10200)
        val vm = viewModel(FakeSettingsRepository(autoUpdate = false))
        advanceUntilIdle()
        assertThat(vm.uiState.value.items.first { it.packageName == "com.lucid.auto.media" }.state)
            .isEqualTo(AppState.INSTALLED)

        // A newer build is published while the screen is open.
        api.catalog = AppCatalog(
            apps = listOf(remoteApp("com.lucid.auto.media", "1.3.0", 10300, title = "Lucid Media")),
        )
        installer.onSuccess = { pkg -> inspector.put(pkg, "1.3.0", 10300) }

        vm.setAutoUpdateEnabled(true)
        advanceUntilIdle()

        // Acting on the stale cache would have found nothing to do.
        assertThat(installer.installedPackages).containsExactly("com.lucid.auto.media")
    }

    @Test
    fun `the flag is reflected in ui state`() = runTest(dispatcher) {
        val vm = viewModel(FakeSettingsRepository(autoUpdate = false))
        advanceUntilIdle()

        vm.uiState.test {
            assertThat(awaitItem().autoUpdateEnabled).isFalse()

            vm.setAutoUpdateEnabled(true)
            advanceUntilIdle()
            // Enabling also kicks off a refresh, so several states are emitted; assert on the
            // one the screen settles on rather than on emission order.
            assertThat(expectMostRecentItem().autoUpdateEnabled).isTrue()

            vm.setAutoUpdateEnabled(false)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().autoUpdateEnabled).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a manual refresh never auto-installs, even with the flag on`() = runTest(dispatcher) {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        val vm = viewModel(FakeSettingsRepository(autoUpdate = true))
        advanceUntilIdle()
        installer.installedPackages.clear()

        vm.refresh(applyAutoUpdate = false)
        advanceUntilIdle()

        assertThat(installer.installedPackages).isEmpty()
    }

    @Test
    fun `changing the base url refetches the catalog`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val before = api.fetchCount

        vm.setCatalogBaseUrl("http://192.168.1.20:8080")
        advanceUntilIdle()

        assertThat(api.fetchCount).isGreaterThan(before)
        assertThat(vm.uiState.value.catalogBaseUrl).isEqualTo("http://192.168.1.20:8080")
    }
}

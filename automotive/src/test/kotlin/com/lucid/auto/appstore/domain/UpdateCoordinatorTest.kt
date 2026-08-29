package com.lucid.auto.appstore.domain

import com.google.common.truth.Truth.assertThat
import com.lucid.auto.appstore.data.catalog.AppCatalog
import com.lucid.auto.appstore.data.catalog.CatalogRepository
import com.lucid.auto.appstore.data.install.InstallOutcome
import com.lucid.auto.appstore.fakes.FakeApkDownloader
import com.lucid.auto.appstore.fakes.FakeAppInstaller
import com.lucid.auto.appstore.fakes.FakeCatalogApi
import com.lucid.auto.appstore.fakes.FakePackageInspector
import com.lucid.auto.appstore.fakes.remoteApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {

    @get:Rule val temp = TemporaryFolder()

    private val SELF_PACKAGE = "com.lucid.auto.appstore"

    private val inspector = FakePackageInspector()
    private val api = FakeCatalogApi()
    private val catalogRepository = CatalogRepository(api)
    private val installer = FakeAppInstaller()

    private lateinit var repository: AppRepository
    private lateinit var downloader: FakeApkDownloader
    private lateinit var coordinator: UpdateCoordinator

    private fun setUp() {
        downloader = FakeApkDownloader(temp.root)
        repository = AppRepository(
            inspector, catalogRepository, SELF_PACKAGE, UnconfinedTestDispatcher(),
        )
        coordinator = UpdateCoordinator(repository, downloader, installer, SELF_PACKAGE)
    }

    private val catalog = AppCatalog(
        apps = listOf(
            remoteApp("com.lucid.auto.media", "1.2.0", 10200),
            remoteApp("com.lucid.auto.radio", "2.0.0", 20000),
            remoteApp("com.spotify.music", "8.9.60", 89060000),
        ),
    )

    @Test
    fun `install downloads then installs and clears progress`() = runTest {
        setUp()
        api.catalog = catalog
        catalogRepository.refresh("http://test")

        val outcome = coordinator.installOrUpdate(catalog.apps.first())

        assertThat(outcome).isEqualTo(InstallOutcome.Success)
        assertThat(downloader.downloadedUrls).hasSize(1)
        assertThat(installer.installedPackages).containsExactly("com.lucid.auto.media")

        val media = repository.currentItems().first { it.packageName == "com.lucid.auto.media" }
        assertThat(media.isBusy).isFalse()
    }

    @Test
    fun `a download failure surfaces as FAILED and never reaches the installer`() = runTest {
        setUp()
        api.catalog = catalog
        catalogRepository.refresh("http://test")
        downloader.forcedFailure = "Integrity check failed"

        val outcome = coordinator.installOrUpdate(catalog.apps.first())

        assertThat(outcome).isInstanceOf(InstallOutcome.Failure::class.java)
        assertThat(installer.installedPackages).isEmpty()

        // The reason is returned to the caller; the row only learns THAT it failed.
        assertThat((outcome as InstallOutcome.Failure).message).isEqualTo("Integrity check failed")
        val media = repository.currentItems().first { it.packageName == "com.lucid.auto.media" }
        assertThat(media.versionLabel).isEqualTo("Failed")
        assertThat(media.versionLabel).doesNotContain("Integrity")
    }

    @Test
    fun `an install failure surfaces the platform message`() = runTest {
        setUp()
        api.catalog = catalog
        catalogRepository.refresh("http://test")
        installer.outcome = InstallOutcome.Failure(4, "The APK is invalid or corrupt")

        val outcome = coordinator.installOrUpdate(catalog.apps.first())

        assertThat((outcome as InstallOutcome.Failure).message)
            .isEqualTo("The APK is invalid or corrupt")
        // The row shows the bare word; the platform's wording never reaches it.
        val media = repository.currentItems().first { it.packageName == "com.lucid.auto.media" }
        assertThat(media.versionLabel).isEqualTo("Failed")
        assertThat(media.versionLabel).doesNotContain("invalid")
    }

    @Test
    fun `applyPendingUpdates only touches apps in UPDATE state`() = runTest {
        setUp()
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)   // UPDATE
        inspector.put("com.lucid.auto.radio", "2.0.0", 20000)   // INSTALLED
        // spotify not installed                                 -> INSTALL
        api.catalog = catalog
        catalogRepository.refresh("http://test")

        // The fake "device" gains the new version once the install reports success.
        installer.onSuccess = { pkg -> inspector.put(pkg, "1.2.0", 10200) }

        val sweep = coordinator.applyPendingUpdates(catalog.byPackage())

        assertThat(sweep.updated).containsExactly("com.lucid.auto.media")
        assertThat(sweep.failed).isEmpty()
        // Crucially: Spotify was NOT auto-installed, and Radio was left alone.
        assertThat(installer.installedPackages).containsExactly("com.lucid.auto.media")
    }

    @Test
    fun `applyPendingUpdates never auto-installs an app that is not on the device`() = runTest {
        setUp()
        // Nothing installed at all: every row is INSTALL.
        api.catalog = catalog
        catalogRepository.refresh("http://test")

        val sweep = coordinator.applyPendingUpdates(catalog.byPackage())

        assertThat(sweep.updated).isEmpty()
        assertThat(installer.installedPackages).isEmpty()
    }

    @Test
    fun `applyPendingUpdates is a no-op when the catalog is empty`() = runTest {
        setUp()
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)

        val sweep = coordinator.applyPendingUpdates(emptyMap())

        assertThat(sweep.updated).isEmpty()
        assertThat(installer.installedPackages).isEmpty()
    }

    @Test
    fun `a failed auto-update is reported in the sweep, not on the row`() = runTest {
        setUp()
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        api.catalog = catalog
        catalogRepository.refresh("http://test")
        downloader.forcedFailure = "Download failed: HTTP 404"

        val sweep = coordinator.applyPendingUpdates(catalog.byPackage())

        assertThat(sweep.updated).isEmpty()
        assertThat(sweep.failed).hasSize(1)
        assertThat(sweep.failed.single().message).contains("404")

        // The row says only "Failed" — the HTTP detail lives in the sweep result.
        val media = repository.currentItems().first { it.packageName == "com.lucid.auto.media" }
        assertThat(media.versionLabel).isEqualTo("Failed")
        assertThat(media.versionLabel).doesNotContain("404")
        assertThat(media.isBusy).isFalse()
        assertThat(media.state).isEqualTo(AppState.UPDATE)
    }
}

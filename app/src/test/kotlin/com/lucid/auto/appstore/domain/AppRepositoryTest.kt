package com.lucid.auto.appstore.domain

import com.google.common.truth.Truth.assertThat
import com.lucid.auto.appstore.data.catalog.AppCatalog
import com.lucid.auto.appstore.data.catalog.CatalogRepository
import com.lucid.auto.appstore.fakes.FakeCatalogApi
import com.lucid.auto.appstore.fakes.FakePackageInspector
import com.lucid.auto.appstore.fakes.remoteApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppRepositoryTest {

    private val inspector = FakePackageInspector()
    private val api = FakeCatalogApi()
    private val catalogRepository = CatalogRepository(api)

    private fun repository() = AppRepository(
        inspector = inspector,
        catalogRepository = catalogRepository,
        selfPackageName = "com.lucid.auto.appstore",
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private val fullCatalog = AppCatalog(
        apps = listOf(
            remoteApp("com.lucid.auto.media", "1.2.0", 10200, title = "Lucid Media"),
            remoteApp("com.lucid.auto.radio", "2.0.0", 20000, title = "Lucid Radio"),
            remoteApp("com.spotify.music", "8.9.60", 89060000, title = "Spotify"),
        ),
    )

    @Test
    fun `merges local and remote into the three product states`() = runTest {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)   // older -> UPDATE
        inspector.put("com.lucid.auto.radio", "2.0.0", 20000)   // current -> INSTALLED
        // Spotify absent -> INSTALL
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")

        val items = repository.currentItems().associateBy { it.packageName }

        assertThat(items).hasSize(3)
        assertThat(items.getValue("com.lucid.auto.media").state).isEqualTo(AppState.UPDATE)
        assertThat(items.getValue("com.lucid.auto.radio").state).isEqualTo(AppState.INSTALLED)
        assertThat(items.getValue("com.spotify.music").state).isEqualTo(AppState.INSTALL)
    }

    @Test
    fun `rows carry both local and available versions`() = runTest {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")

        val media = repository.currentItems().first { it.packageName == "com.lucid.auto.media" }

        assertThat(media.installedVersionName).isEqualTo("1.1.0")
        assertThat(media.installedVersionCode).isEqualTo(10100L)
        assertThat(media.availableVersionName).isEqualTo("1.2.0")
        assertThat(media.availableVersionCode).isEqualTo(10200L)
        assertThat(media.versionLabel).isEqualTo("1.1.0 → 1.2.0")
    }

    @Test
    fun `the catalog drives the list - device apps outside it are never shown`() = runTest {
        // A device with plenty of apps installed...
        repeat(50) { i -> inspector.put("com.other.app$i", "1.0.0", 1) }
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        inspector.put("com.lucid.auto.radio", "2.0.0", 20000)
        // ...and a catalogue naming only three of them.
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")

        val packages = repository.currentItems().map { it.packageName }

        assertThat(packages).containsExactly(
            "com.lucid.auto.media",
            "com.lucid.auto.radio",
            "com.spotify.music",
        )
    }

    @Test
    fun `list size follows the catalog, not the device`() = runTest {
        repeat(50) { i -> inspector.put("com.other.app$i", "1.0.0", 1) }
        api.catalog = AppCatalog(
            apps = (1..10).map { remoteApp("com.catalog.app$it", "1.0.0", 100) },
        )
        val repository = repository()
        catalogRepository.refresh("http://test")

        // 50 apps on the device, 10 in the catalogue -> 10 rows.
        assertThat(repository.currentItems()).hasSize(10)
    }

    @Test
    fun `the store never lists itself, even when the catalog offers it`() = runTest {
        inspector.put("com.lucid.auto.appstore", "1.0.0", 1)
        api.catalog = AppCatalog(
            apps = listOf(
                remoteApp("com.lucid.auto.media", "1.2.0", 10200),
                remoteApp("com.lucid.auto.appstore", "2.0.0", 2, title = "Lucid App Store"),
            ),
        )
        val repository = repository()
        catalogRepository.refresh("http://test")

        val packages = repository.currentItems().map { it.packageName }

        assertThat(packages).containsExactly("com.lucid.auto.media")
        assertThat(packages).doesNotContain("com.lucid.auto.appstore")
    }

    @Test
    fun `an unreachable catalog with no cache shows nothing, not stale device apps`() = runTest {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100, label = "Lucid Media")
        api.error = java.io.IOException("connection refused")
        val repository = repository()

        val result = repository.refreshCatalog("http://unreachable")

        assertThat(result.isFailure).isTrue()
        // Nothing has ever been fetched, so there is no catalogue to drive a list.
        assertThat(repository.currentItems()).isEmpty()
    }

    @Test
    fun `a failed refresh keeps showing the last good catalog`() = runTest {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")
        assertThat(repository.currentItems()).hasSize(3)

        api.error = java.io.IOException("network down")
        assertThat(repository.refreshCatalog("http://test").isFailure).isTrue()

        // The cached catalogue still drives the list, so the car keeps a usable screen offline.
        assertThat(repository.currentItems()).hasSize(3)
    }

    @Test
    fun `in-flight work shows status text but never changes the row's state`() = runTest {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")

        suspend fun media() =
            repository.currentItems().first { it.packageName == "com.lucid.auto.media" }

        repository.setProgress("com.lucid.auto.media", InstallProgress.Downloading(37))
        assertThat(media().versionLabel).isEqualTo("Downloading… 37%")
        assertThat(media().state).isEqualTo(AppState.UPDATE)     // state never changes
        assertThat(media().actionLabel).isEqualTo("Update")      // nor does the control
        assertThat(media().isBusy).isTrue()

        repository.setProgress("com.lucid.auto.media", InstallProgress.Installing)
        assertThat(media().versionLabel).isEqualTo("Installing…")
        assertThat(media().state).isEqualTo(AppState.UPDATE)
        assertThat(media().isBusy).isTrue()

        repository.setProgress("com.lucid.auto.media", InstallProgress.Failed)
        assertThat(media().versionLabel).isEqualTo("Failed")
        assertThat(media().state).isEqualTo(AppState.UPDATE)
        assertThat(media().isBusy).isFalse()                      // failed is not "working"
        assertThat(media().isActionable).isTrue()                 // tapping retries

        repository.setProgress("com.lucid.auto.media", null)
        assertThat(media().versionLabel).isEqualTo("1.1.0 → 1.2.0")
        assertThat(media().isBusy).isFalse()
    }

    @Test
    fun `an icon is decoded once and reused across rebuilds`() = runTest {
        inspector.iconsAvailable = true
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")

        // Simulate a download: one rebuild per percent, as the real downloader produces.
        repeat(100) { percent ->
            repository.setProgress("com.lucid.auto.media", InstallProgress.Downloading(percent))
            repository.currentItems()
        }

        // Without the cache this would be ~100 drawable decodes.
        assertThat(inspector.iconDecodes["com.lucid.auto.media"]).isEqualTo(1)
    }

    @Test
    fun `a version bump re-decodes the icon`() = runTest {
        inspector.iconsAvailable = true
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")
        repository.currentItems()
        assertThat(inspector.iconDecodes["com.lucid.auto.media"]).isEqualTo(1)

        // The app updates: same package, new versionCode -> the cached icon must not be reused.
        inspector.put("com.lucid.auto.media", "1.2.0", 10200)
        repository.currentItems()

        assertThat(inspector.iconDecodes["com.lucid.auto.media"]).isEqualTo(2)
    }

    @Test
    fun `versions are re-read every rebuild - only icons are cached`() = runTest {
        // The device is the source of truth for versions; caching those would go stale when
        // something outside the store installs or uninstalls an app.
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")

        repository.currentItems()
        repository.currentItems()
        repository.currentItems()

        assertThat(inspector.versionReads["com.lucid.auto.media"]).isEqualTo(3)
    }

    @Test
    fun `an app uninstalled outside the store is reflected immediately`() = runTest {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")
        assertThat(
            repository.currentItems().first { it.packageName == "com.lucid.auto.media" }.state,
        ).isEqualTo(AppState.UPDATE)

        // Someone uninstalls it from Settings - no notification to the store.
        inspector.remove("com.lucid.auto.media")

        assertThat(
            repository.currentItems().first { it.packageName == "com.lucid.auto.media" }.state,
        ).isEqualTo(AppState.INSTALL)
    }

    @Test
    fun `clearFailures drops failed markers but leaves work in flight alone`() = runTest {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100)
        inspector.put("com.lucid.auto.radio", "1.0.0", 100)
        api.catalog = fullCatalog
        val repository = repository()
        catalogRepository.refresh("http://test")

        repository.setProgress("com.lucid.auto.media", InstallProgress.Failed)
        repository.setProgress("com.lucid.auto.radio", InstallProgress.Downloading(50))

        repository.clearFailures()

        val byPackage = repository.currentItems().associateBy { it.packageName }
        assertThat(byPackage.getValue("com.lucid.auto.media").progress).isNull()
        assertThat(byPackage.getValue("com.lucid.auto.radio").versionLabel)
            .isEqualTo("Downloading… 50%")
    }

    @Test
    fun `a failed refresh keeps the previously cached catalog`() = runTest {
        api.catalog = fullCatalog
        val repository = repository()
        assertThat(repository.refreshCatalog("http://test").isSuccess).isTrue()

        api.error = java.io.IOException("network down")
        assertThat(repository.refreshCatalog("http://test").isFailure).isTrue()

        assertThat(catalogRepository.cached().apps).hasSize(3)
    }
}

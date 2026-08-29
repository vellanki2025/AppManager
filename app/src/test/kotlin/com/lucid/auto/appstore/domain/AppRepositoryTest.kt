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
    fun `an unreachable catalog still lists installed apps as INSTALLED`() = runTest {
        inspector.put("com.lucid.auto.media", "1.1.0", 10100, label = "Lucid Media")
        api.error = java.io.IOException("connection refused")
        val repository = repository()

        val result = repository.refreshCatalog("http://unreachable")
        val items = repository.currentItems()

        assertThat(result.isFailure).isTrue()
        assertThat(items).hasSize(1)   // radio + spotify are unknown to both sides, so omitted
        assertThat(items.single().packageName).isEqualTo("com.lucid.auto.media")
        assertThat(items.single().state).isEqualTo(AppState.INSTALLED)
    }

    @Test
    fun `an app known to neither side is omitted from the list`() = runTest {
        api.catalog = AppCatalog(apps = listOf(remoteApp("com.lucid.auto.media", "1.2.0", 10200)))
        val repository = repository()
        catalogRepository.refresh("http://test")

        val packages = repository.currentItems().map { it.packageName }

        assertThat(packages).containsExactly("com.lucid.auto.media")
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

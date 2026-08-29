package com.lucid.auto.appstore.domain

import androidx.compose.ui.graphics.ImageBitmap
import com.lucid.auto.appstore.data.catalog.AppCatalog
import com.lucid.auto.appstore.data.catalog.CatalogRepository
import com.lucid.auto.appstore.data.local.PackageInspector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Merges what is on the device with what the server offers, and produces the rows the UI draws.
 *
 * The "local version" half of every row comes from [PackageInspector]; the "available version"
 * half from [CatalogRepository]; and [AppStateResolver] turns the pair into an [AppState].
 */
class AppRepository(
    private val inspector: PackageInspector,
    private val catalogRepository: CatalogRepository,
    private val managedApps: List<ManagedApp> = ManagedApps.ALL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Bumped after every install so the local snapshot is re-read (FR-5.6). */
    private val localRefreshTrigger = MutableStateFlow(0)

    private val _progress = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())
    val progress: Flow<Map<String, InstallProgress>> = _progress.asStateFlow()

    private val _remoteIcons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    val items: Flow<List<AppItem>> = combine(
        catalogRepository.catalog,
        localRefreshTrigger,
        _progress,
        _remoteIcons,
    ) { catalog, _, progress, remoteIcons ->
        buildItems(catalog, progress, remoteIcons)
    }

    /** Re-reads local versions from PackageManager. Cheap; safe to call after every install. */
    suspend fun refreshLocal() = withContext(ioDispatcher) {
        localRefreshTrigger.value += 1
    }

    suspend fun refreshCatalog(baseUrl: String): Result<AppCatalog> =
        catalogRepository.refresh(baseUrl)

    /** The rows as they are right now, computed off the UI thread. */
    suspend fun currentItems(): List<AppItem> = withContext(ioDispatcher) {
        buildItems(catalogRepository.cached(), _progress.value, _remoteIcons.value)
    }

    /**
     * Drops any lingering `Failed` markers.
     *
     * A failure marker is a note about the *last attempt*, not about the app, so a fresh refresh
     * clears it. In-flight work is left alone.
     */
    fun clearFailures() {
        _progress.value = _progress.value.filterValues { it != InstallProgress.Failed }
    }

    fun setProgress(packageName: String, progress: InstallProgress?) {
        _progress.value = _progress.value.toMutableMap().apply {
            if (progress == null) remove(packageName) else put(packageName, progress)
        }
    }

    fun cacheRemoteIcon(packageName: String, icon: ImageBitmap) {
        _remoteIcons.value = _remoteIcons.value + (packageName to icon)
    }

    fun hasRemoteIcon(packageName: String): Boolean = _remoteIcons.value.containsKey(packageName)

    private fun buildItems(
        catalog: AppCatalog,
        progress: Map<String, InstallProgress>,
        remoteIcons: Map<String, ImageBitmap>,
    ): List<AppItem> {
        val remoteByPackage = catalog.byPackage()

        return managedApps.mapNotNull { managed ->
            val pkg = managed.packageName
            val local = inspector.getInstalled(pkg)
            val remote = remoteByPackage[pkg]

            if (!AppStateResolver.isListable(local, remote)) return@mapNotNull null

            val resolved = AppStateResolver.resolve(local, remote)
            val work = progress[pkg]

            AppItem(
                packageName = pkg,
                title = remote?.title ?: local?.label ?: managed.fallbackTitle,
                icon = if (local != null) inspector.getIcon(pkg) else remoteIcons[pkg],
                installedVersionName = local?.versionName,
                installedVersionCode = local?.versionCode,
                availableVersionName = remote?.versionName,
                availableVersionCode = remote?.versionCode,
                // The resolved state always wins: work in flight overlays short status text on
                // the version line, but never changes which of the three states the row is in.
                state = resolved,
                progress = work,
                releaseNotes = remote?.releaseNotes,
            )
        }
    }
}

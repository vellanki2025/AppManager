package com.lucid.auto.appstore.domain

import androidx.compose.ui.graphics.ImageBitmap
import com.lucid.auto.appstore.data.catalog.AppCatalog
import com.lucid.auto.appstore.data.catalog.CatalogRepository
import com.lucid.auto.appstore.data.local.InstalledApp
import com.lucid.auto.appstore.data.local.PackageInspector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Merges what is on the device with what the server offers, and produces the rows the UI draws.
 *
 * The "local version" half of every row comes from [PackageInspector]; the "available version"
 * half from [CatalogRepository]; and [AppStateResolver] turns the pair into an [AppState].
 */
class AppRepository(
    private val inspector: PackageInspector,
    private val catalogRepository: CatalogRepository,
    /**
     * This app's own package. It is filtered out of the list: the store updates itself through
     * [UpdateCoordinator.applySelfUpdate], but is never shown as a row.
     */
    private val selfPackageName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Bumped after every install so the local snapshot is re-read (FR-5.6). */
    private val localRefreshTrigger = MutableStateFlow(0)

    private val _progress = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())
    val progress: Flow<Map<String, InstallProgress>> = _progress.asStateFlow()

    private val _remoteIcons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /**
     * The rows the UI renders.
     *
     * `flowOn` is load-bearing, not decoration. A `combine` transform runs on the *collector's*
     * context, and the collector is the ViewModel on the main thread — so without it every
     * rebuild would do Binder calls into PackageManager and decode app icons on the UI thread,
     * violating NFR-2. `_progress` is one of the combined sources and ticks once per percent of
     * a download, so that is ~100 rebuilds per install.
     *
     * `conflate` then lets a burst of those emissions collapse into a single recomposition when
     * the UI is behind, instead of queueing every intermediate list.
     */
    val items: Flow<List<AppItem>> = combine(
        catalogRepository.catalog,
        localRefreshTrigger,
        _progress,
        _remoteIcons,
    ) { catalog, _, progress, remoteIcons ->
        buildItems(catalog, progress, remoteIcons)
    }
        .flowOn(ioDispatcher)
        .conflate()

    /**
     * Decoded launcher icons, keyed by package.
     *
     * Loading an icon means reading and decoding a drawable out of the app's APK — an order of
     * magnitude dearer than reading its version, and pure waste to repeat on every progress tick.
     *
     * The `versionCode` is the invalidation key, and it cannot go stale: a package's icon cannot
     * change without its APK changing, and its APK cannot change without its versionCode changing.
     * A failed decode is cached as `null` too, so a broken icon is not retried on every rebuild.
     */
    private val iconCache = ConcurrentHashMap<String, CachedIcon>()

    private data class CachedIcon(val versionCode: Long, val icon: ImageBitmap?)

    /** Re-reads local versions from PackageManager. Cheap; safe to call after every install. */
    suspend fun refreshLocal() = withContext(ioDispatcher) {
        localRefreshTrigger.value += 1
    }

    suspend fun refreshCatalog(baseUrl: String): Result<AppCatalog> =
        catalogRepository.refresh(baseUrl)

    /** Reads one package's installed state directly. Used by the self-update check. */
    suspend fun installed(packageName: String): InstalledApp? = withContext(ioDispatcher) {
        inspector.getInstalled(packageName)
    }

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

    private fun localIcon(packageName: String, versionCode: Long): ImageBitmap? {
        iconCache[packageName]?.let { if (it.versionCode == versionCode) return it.icon }
        val icon = inspector.getIcon(packageName)
        iconCache[packageName] = CachedIcon(versionCode, icon)
        return icon
    }

    private fun buildItems(
        catalog: AppCatalog,
        progress: Map<String, InstallProgress>,
        remoteIcons: Map<String, ImageBitmap>,
    ): List<AppItem> {
        // The catalogue drives the list. The device is only consulted for the version of each
        // app the catalogue names, so a device with 50 apps and a catalogue of 10 shows 10 rows.
        // Apps installed on the device but absent from the catalogue are not the store's concern
        // and are never listed.
        return catalog.apps.mapNotNull { remote ->
            val pkg = remote.packageName

            // The store manages itself, but never lists itself.
            if (pkg == selfPackageName) return@mapNotNull null

            val local = inspector.getInstalled(pkg)
            val resolved = AppStateResolver.resolve(local, remote)
            val work = progress[pkg]

            AppItem(
                packageName = pkg,
                title = remote.title.ifBlank { local?.label ?: pkg },
                icon = if (local != null) localIcon(pkg, local.versionCode) else remoteIcons[pkg],
                installedVersionName = local?.versionName,
                installedVersionCode = local?.versionCode,
                availableVersionName = remote.versionName,
                availableVersionCode = remote.versionCode,
                // The resolved state always wins: work in flight overlays short status text on
                // the version line, but never changes which of the three states the row is in.
                state = resolved,
                progress = work,
                releaseNotes = remote.releaseNotes,
            )
        }
    }
}

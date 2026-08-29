package com.lucid.auto.appstore.di

import android.content.Context
import androidx.work.WorkManager
import com.lucid.auto.appstore.data.catalog.CatalogRepository
import com.lucid.auto.appstore.data.catalog.HttpCatalogApi
import com.lucid.auto.appstore.data.download.ApkDownloader
import com.lucid.auto.appstore.data.download.HttpApkDownloader
import com.lucid.auto.appstore.data.download.HttpRemoteIconLoader
import com.lucid.auto.appstore.data.download.RemoteIconLoader
import com.lucid.auto.appstore.data.install.AppInstaller
import com.lucid.auto.appstore.data.install.SessionAppInstaller
import com.lucid.auto.appstore.data.local.PackageInspector
import com.lucid.auto.appstore.data.local.PackageManagerInspector
import com.lucid.auto.appstore.data.settings.DataStoreSettingsRepository
import com.lucid.auto.appstore.data.settings.SettingsRepository
import com.lucid.auto.appstore.domain.AppRepository
import com.lucid.auto.appstore.domain.UpdateCoordinator
import com.lucid.auto.appstore.update.AutoUpdateScheduler
import com.lucid.auto.appstore.update.WorkManagerAutoUpdateScheduler
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency graph.
 *
 * Every collaborator behind an interface with a fake, so tests construct objects directly and no
 * DI framework earns its place in a three-app store.
 */
class AppGraph(
    val packageInspector: PackageInspector,
    val catalogRepository: CatalogRepository,
    val appRepository: AppRepository,
    val updateCoordinator: UpdateCoordinator,
    val settingsRepository: SettingsRepository,
    val autoUpdateScheduler: AutoUpdateScheduler,
    val remoteIconLoader: RemoteIconLoader,
    val installer: AppInstaller,
    val downloader: ApkDownloader,
)

object ServiceLocator {

    @Volatile
    private var graph: AppGraph? = null

    /** Test seam: install a fully faked graph before exercising workers or receivers. */
    fun override(testGraph: AppGraph?) {
        graph = testGraph
    }

    fun from(context: Context): AppGraph =
        graph ?: synchronized(this) { graph ?: build(context.applicationContext).also { graph = it } }

    private fun build(context: Context): AppGraph {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()

        val selfPackageName = context.packageName
        val inspector = PackageManagerInspector(context)
        val catalogRepository = CatalogRepository(HttpCatalogApi(client))
        val appRepository = AppRepository(inspector, catalogRepository, selfPackageName)
        val downloader = HttpApkDownloader(client, File(context.cacheDir, "apks"))
        val installer = SessionAppInstaller(context)

        return AppGraph(
            packageInspector = inspector,
            catalogRepository = catalogRepository,
            appRepository = appRepository,
            updateCoordinator = UpdateCoordinator(
                appRepository, downloader, installer, selfPackageName,
            ),
            settingsRepository = DataStoreSettingsRepository(context),
            autoUpdateScheduler = WorkManagerAutoUpdateScheduler(WorkManager.getInstance(context)),
            remoteIconLoader = HttpRemoteIconLoader(client),
            installer = installer,
            downloader = downloader,
        )
    }
}

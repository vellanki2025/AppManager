package com.lucid.appmanager

import android.app.Application
import com.lucid.appmanager.catalogue.StubApkUrlProvider
import com.lucid.appmanager.catalogue.StubCatalogueProvider
import com.lucid.appmanager.download.DownloadComponent
import com.lucid.appmanager.download.config.DataStoreConfigRepository
import com.lucid.appmanager.download.engine.DownloadEngineFactory
import com.lucid.appmanager.download.engine.HttpDownloadEngine
import com.lucid.appmanager.install.InstallMode
import com.lucid.appmanager.signals.VehicleBatteryMonitor
import com.lucid.appmanager.signals.VehicleConnectivitySubscription
import com.lucid.appmanager.signals.VehicleDrivingStateProvider

/**
 * Composition root. Connect the vehicle signal library inside the adapters in `signals/`.
 *
 * [dependencies] is `open` so the debug build can decorate what is built here without this class knowing
 * the debug source set exists. The debug manifest points `android:name` at `DebugApplication`, which
 * subclasses this one — see requirements FR-57.
 */
open class AppManagerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DownloadComponent.install(dependencies()).start()
    }

    protected open fun dependencies(): DownloadComponent.Dependencies =
        DownloadComponent.Dependencies(
            context = this,
            config = DataStoreConfigRepository(this),
            driving = VehicleDrivingStateProvider(),
            battery = VehicleBatteryMonitor(),
            subscription = VehicleConnectivitySubscription(),
            catalogue = StubCatalogueProvider(),
            urls = StubApkUrlProvider(),
            engineFactory = DownloadEngineFactory { HttpDownloadEngine(it) },
            debug = BuildConfig.DEBUG,
            featureDefaultEnabled = BuildConfig.DOWNLOAD_SERVICE_ENABLED,
            featureDefaultInstallMode = InstallMode.parse(BuildConfig.INSTALL_MODE),
        )
}

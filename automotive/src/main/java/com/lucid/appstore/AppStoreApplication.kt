package com.lucid.appstore

import android.app.Application
import com.lucid.appstore.catalogue.CatalogueProvider
import com.lucid.appstore.catalogue.StubCatalogueProvider
import com.lucid.appstore.download.DownloadComponent
import com.lucid.appstore.download.config.DataStoreConfigRepository
import com.lucid.appstore.download.engine.FakeDownloadEngine
import com.lucid.appstore.signals.VehicleBatteryMonitor
import com.lucid.appstore.signals.VehicleDrivingStateProvider

/** Composition root. Connect the vehicle signal library inside the two adapters in `signals/`. */
class AppStoreApplication : Application() {
    val catalogue: CatalogueProvider = StubCatalogueProvider()

    override fun onCreate() {
        super.onCreate()
        val config = DataStoreConfigRepository(this)
        DownloadComponent.install(
            DownloadComponent.Dependencies(
                context = this,
                config = config,
                driving = VehicleDrivingStateProvider(),
                battery = VehicleBatteryMonitor(),
                engine = FakeDownloadEngine(),
                debug = BuildConfig.DEBUG,
                featureDefaultEnabled = BuildConfig.DOWNLOAD_SERVICE_ENABLED,
            ),
        ).start()
    }
}

package com.lucid.appstore

import android.app.Application
import com.lucid.appstore.catalogue.CatalogueProvider
import com.lucid.appstore.catalogue.StubCatalogueProvider
import com.lucid.appstore.download.DownloadComponent
import com.lucid.appstore.download.config.DataStoreConfigRepository
import com.lucid.appstore.download.engine.FakeDownloadEngine
import com.lucid.appstore.vehicle.CarDrivingStateProvider
import com.lucid.appstore.vehicle.CarEvBatteryMonitor
import kotlinx.coroutines.flow.map

/** Composition root. Swap the vehicle signal sources or the engine here. */
class AppStoreApplication : Application() {
    val catalogue: CatalogueProvider = StubCatalogueProvider()

    override fun onCreate() {
        super.onCreate()
        val config = DataStoreConfigRepository(this)
        DownloadComponent.install(
            DownloadComponent.Dependencies(
                context = this,
                config = config,
                driving = CarDrivingStateProvider(this, config.config.map { it.requireParkingBrake }),
                battery = CarEvBatteryMonitor(this),
                engine = FakeDownloadEngine(),
                debug = BuildConfig.DEBUG,
                featureDefaultEnabled = BuildConfig.DOWNLOAD_SERVICE_ENABLED,
            ),
        ).start()
    }
}

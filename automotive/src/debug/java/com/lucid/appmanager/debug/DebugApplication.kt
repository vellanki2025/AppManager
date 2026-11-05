package com.lucid.appmanager.debug

import com.lucid.appmanager.AppManagerApplication
import com.lucid.appmanager.download.DownloadComponent
import com.lucid.appmanager.download.engine.DownloadEngineFactory
import com.lucid.appmanager.download.engine.HttpDownloadEngine
import com.lucid.appmanager.install.InstallMode
import com.lucid.appmanager.install.Installer
import com.lucid.appmanager.install.PackageManagerInstaller

/**
 * Debug composition root (FR-57).
 *
 * `src/main` never names anything in this package. The debug manifest points `android:name` at this class
 * instead of [AppManagerApplication]; the manifest merger does the substitution, and the only seam main
 * exposes is an `open` [AppManagerApplication.dependencies].
 *
 * Every override is a decorator in front of the real source, so an untouched debug build behaves exactly
 * like release until a [DebugFlagActions] call changes something.
 */
class DebugApplication : AppManagerApplication() {

    override fun dependencies(): DownloadComponent.Dependencies {
        val real = super.dependencies()

        val driving = DebugDrivingProvider(real.driving)
        val battery = DebugBatteryMonitor(real.battery)
        val network = DebugNetworkMonitor(real.network)
        val subscription = DebugSubscriptionProvider(real.subscription)

        Decorators.driving = driving
        Decorators.battery = battery
        Decorators.network = network
        Decorators.subscription = subscription

        return DownloadComponent.Dependencies(
            context = real.context,
            driving = driving,
            battery = battery,
            subscription = subscription,
            config = real.config,
            network = network,
            clock = real.clock,
            debug = true,
            featureDefaultEnabled = real.featureDefaultEnabled,
            featureDefaultInstallMode = real.featureDefaultInstallMode,
            catalogue = DebugCatalogueProvider(real.catalogue),
            urls = DebugUrlProvider(real.urls),
            downloadDir = real.downloadDir,
            storage = DebugStorageMonitor(real.storage),
            engineFactory = DownloadEngineFactory { request ->
                DebugDownloadEngine(request, HttpDownloadEngine(request))
            },
            installerFactory = { mode, context, _, _, overrides ->
                val delegate: Installer = when (mode) {
                    InstallMode.PACKAGE_MANAGER -> PackageManagerInstaller(context)
                    InstallMode.APK_OVERRIDE -> overrides
                }
                DebugInstaller(delegate)
            },
        )
    }
}

/** Handles to the live decorators, so [DebugFlagActions] can push overrides into the running graph. */
internal object Decorators {
    lateinit var driving: DebugDrivingProvider
    lateinit var battery: DebugBatteryMonitor
    lateinit var network: DebugNetworkMonitor
    lateinit var subscription: DebugSubscriptionProvider
}

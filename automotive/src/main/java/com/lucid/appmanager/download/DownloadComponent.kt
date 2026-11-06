package com.lucid.appmanager.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.work.WorkManager
import com.lucid.appmanager.download.config.ConfigRepository
import com.lucid.appmanager.download.config.DataStoreConfigRepository
import com.lucid.appmanager.download.config.DataStoreFeatureFlags
import com.lucid.appmanager.download.config.FeatureFlags
import com.lucid.appmanager.catalogue.ManifestChecker
import com.lucid.appmanager.catalogue.ApkUrlProvider
import com.lucid.appmanager.catalogue.CatalogueProvider
import com.lucid.appmanager.catalogue.StubApkUrlProvider
import com.lucid.appmanager.catalogue.StubCatalogueProvider
import com.lucid.appmanager.download.engine.DownloadEngineFactory
import com.lucid.appmanager.download.engine.FakeDownloadEngine
import com.lucid.appmanager.download.engine.HttpDownloadEngine
import com.lucid.appmanager.download.events.DefaultEventBus
import com.lucid.appmanager.download.events.EventBus
import com.lucid.appmanager.download.events.EventType
import com.lucid.appmanager.download.events.Logger
import com.lucid.appmanager.download.events.NotificationController
import com.lucid.appmanager.download.events.ServiceEvent
import com.lucid.appmanager.download.monitor.BatteryMonitor
import com.lucid.appmanager.download.monitor.ConnectivityNetworkMonitor
import com.lucid.appmanager.download.monitor.ConnectivitySubscriptionProvider
import com.lucid.appmanager.download.monitor.DefaultDeviceStateMonitor
import com.lucid.appmanager.download.monitor.DeviceStateMonitor
import com.lucid.appmanager.download.monitor.DrivingStateProvider
import com.lucid.appmanager.download.monitor.FileStorageMonitor
import com.lucid.appmanager.download.monitor.NetworkMonitor
import com.lucid.appmanager.download.monitor.StorageMonitor
import com.lucid.appmanager.download.verify.IntegrityVerifier
import com.lucid.appmanager.install.InstallMode
import com.lucid.appmanager.install.Installer
import com.lucid.appmanager.install.OverrideStagingInstaller
import com.lucid.appmanager.install.PackageManagerInstaller
import com.lucid.appmanager.install.PendingOverrideStore
import com.lucid.appmanager.install.OverrideCheck
import com.lucid.appmanager.download.policy.DefaultPolicyEvaluator
import com.lucid.appmanager.download.run.DataStoreRunStateStore
import com.lucid.appmanager.download.run.RunCoordinator
import com.lucid.appmanager.download.run.RunStateStore
import com.lucid.appmanager.download.run.WaitingWatcher
import com.lucid.appmanager.download.scheduling.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock

/**
 * Wires the download service together. The host app calls [install] from `Application.onCreate` with its
 * signal sources, then [start]. Workers and receivers reach the graph through [get].
 */
class DownloadComponent private constructor(
    val context: Context,
    val scope: CoroutineScope,
    val config: ConfigRepository,
    val flags: FeatureFlags,
    val runStateStore: RunStateStore,
    val monitor: DeviceStateMonitor,
    val eventBus: EventBus,
    val notifications: NotificationController,
    val scheduler: Scheduler,
    val coordinator: RunCoordinator,
    val catalogue: CatalogueProvider,
    val checker: ManifestChecker,
    val overrides: OverrideStagingInstaller,
    private val waitingWatcher: WaitingWatcher,
) {
    /** Starts the in-process observers: waiting watcher, feature flag, config changes, time changes. */
    fun start() {
        waitingWatcher.start(scope)
        // FR-50: the reboot may be days after staging, with no run in flight, so this is checked here
        // rather than inside the run pipeline.
        scope.launch {
            when (val check = overrides.checkApplied()) {
                is OverrideCheck.Applied ->
                    eventBus.emit(ServiceEvent(EventType.OVERRIDE_APPLIED, message = "${check.packageName} now at ${check.versionCode}"))
                is OverrideCheck.NotApplied ->
                    eventBus.emit(ServiceEvent(EventType.OVERRIDE_NOT_APPLIED, message = "rebooted but ${check.override.packageName} is still ${check.installedVersionCode}"))
                else -> Unit
            }
        }
        scope.launch {
            flags.flags.map { it.downloadServiceEnabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) scheduler.ensureScheduled() else coordinator.cancelCurrentRun(EventType.SERVICE_DISABLED).also { scheduler.cancelAll() }
            }
        }
        scope.launch {
            config.config.drop(1).collect { eventBus.emit(ServiceEvent(EventType.CONFIG_CHANGED, config = it, message = it.toString())) }
        }
        scope.launch {
            config.config.map { it.scheduleTime }.distinctUntilChanged().drop(1).collect { scheduler.reschedule() }
        }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { scope.launch { scheduler.reschedule() } }
            },
            IntentFilter().apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) },
        )
    }

    /** Everything the host must or may supply. Vehicle signals are required; the rest has defaults. */
    class Dependencies(
        val context: Context,
        val driving: DrivingStateProvider,
        val battery: BatteryMonitor,
        val subscription: ConnectivitySubscriptionProvider,
        val config: ConfigRepository,
        val network: NetworkMonitor = ConnectivityNetworkMonitor(context),
        val clock: Clock = Clock.systemDefaultZone(),
        /** Debug builds log all failing rules and show a Toast per event. */
        val debug: Boolean = false,
        val featureDefaultEnabled: Boolean = true,
        val featureDefaultInstallMode: InstallMode = InstallMode.PACKAGE_MANAGER,
        val catalogue: CatalogueProvider = StubCatalogueProvider(),
        val urls: ApkUrlProvider = StubApkUrlProvider(),
        /** Where the download writes. Also the volume the storage rule measures. */
        val downloadDir: File = File(context.cacheDir, "download"),
        val storage: StorageMonitor = FileStorageMonitor(downloadDir),
        /**
         * One engine per run, because the engine now holds per-run state (URL session, target file).
         * Defaults to the simulation so the status screen works without a backend; release wiring passes
         * [HttpDownloadEngine].
         */
        val engineFactory: DownloadEngineFactory = DownloadEngineFactory { FakeDownloadEngine() },
        /** Override to decorate the installers, which is how the debug build forces install failures. */
        val installerFactory: ((InstallMode, Context, PendingOverrideStore, ManifestChecker, OverrideStagingInstaller) -> Installer)? = null,
    )

    companion object {
        @Volatile private var instance: DownloadComponent? = null

        fun get(): DownloadComponent = checkNotNull(instance) { "DownloadComponent.install() must run in Application.onCreate" }

        fun install(deps: Dependencies): DownloadComponent {
            val app = deps.context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val flags = DataStoreFeatureFlags(app, deps.featureDefaultEnabled, deps.featureDefaultInstallMode)
            val store = DataStoreRunStateStore(app, deps.clock)
            val monitor = DefaultDeviceStateMonitor(deps.battery, deps.network, deps.driving, deps.subscription, deps.storage)
            val notifications = NotificationController(app)
            val events = DefaultEventBus(listOf(Logger(app, debugToasts = deps.debug), notifications))
            val policy = DefaultPolicyEvaluator(logAllFailures = deps.debug) { Log.d(Logger.TAG, it) }
            val scheduler = Scheduler(WorkManager.getInstance(app), deps.config, flags, deps.clock)

            val checker = ManifestChecker(deps.catalogue, app.packageManager)
            val verifier = IntegrityVerifier(app.packageManager)
            val overrideStore = PendingOverrideStore(app)
            val overrides = OverrideStagingInstaller(app, overrideStore, checker)
            val installerFor: (InstallMode) -> Installer = { mode ->
                deps.installerFactory?.invoke(mode, app, overrideStore, checker, overrides)
                    ?: when (mode) {
                        InstallMode.PACKAGE_MANAGER -> PackageManagerInstaller(app)
                        InstallMode.APK_OVERRIDE -> overrides
                    }
            }

            val coordinator = RunCoordinator(
                store, deps.config, flags, monitor, policy, deps.engineFactory, scheduler, events, notifications,
                deps.clock, checker, deps.urls, verifier, installerFor, deps.downloadDir,
            )
            val watcher = WaitingWatcher(store, deps.config, monitor, policy, scheduler, events)
            return DownloadComponent(
                app, scope, deps.config, flags, store, monitor, events, notifications, scheduler, coordinator,
                deps.catalogue, checker, overrides, watcher,
            ).also { instance = it }
        }
    }
}

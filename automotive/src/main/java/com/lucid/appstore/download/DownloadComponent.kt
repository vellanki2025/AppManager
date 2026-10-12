package com.lucid.appstore.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.work.WorkManager
import com.lucid.appstore.download.config.ConfigRepository
import com.lucid.appstore.download.config.DataStoreConfigRepository
import com.lucid.appstore.download.config.DataStoreFeatureFlags
import com.lucid.appstore.download.config.FeatureFlags
import com.lucid.appstore.download.engine.DownloadEngine
import com.lucid.appstore.download.engine.FakeDownloadEngine
import com.lucid.appstore.download.events.DefaultEventBus
import com.lucid.appstore.download.events.EventBus
import com.lucid.appstore.download.events.EventType
import com.lucid.appstore.download.events.Logger
import com.lucid.appstore.download.events.NotificationController
import com.lucid.appstore.download.events.ServiceEvent
import com.lucid.appstore.download.monitor.BatteryMonitor
import com.lucid.appstore.download.monitor.ConnectivityNetworkMonitor
import com.lucid.appstore.download.monitor.ConnectivitySubscriptionProvider
import com.lucid.appstore.download.monitor.DefaultDeviceStateMonitor
import com.lucid.appstore.download.monitor.DeviceStateMonitor
import com.lucid.appstore.download.monitor.DrivingStateProvider
import com.lucid.appstore.download.monitor.NetworkMonitor
import com.lucid.appstore.download.policy.DefaultPolicyEvaluator
import com.lucid.appstore.download.run.DataStoreRunStateStore
import com.lucid.appstore.download.run.RunCoordinator
import com.lucid.appstore.download.run.RunStateStore
import com.lucid.appstore.download.run.WaitingWatcher
import com.lucid.appstore.download.scheduling.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val waitingWatcher: WaitingWatcher,
) {
    /** Starts the in-process observers: waiting watcher, feature flag, config changes, time changes. */
    fun start() {
        waitingWatcher.start(scope)
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
        val engine: DownloadEngine = FakeDownloadEngine(),
        val clock: Clock = Clock.systemDefaultZone(),
        /** Debug builds log all failing rules and show a Toast per event. */
        val debug: Boolean = false,
        val featureDefaultEnabled: Boolean = true,
    )

    companion object {
        @Volatile private var instance: DownloadComponent? = null

        fun get(): DownloadComponent = checkNotNull(instance) { "DownloadComponent.install() must run in Application.onCreate" }

        fun install(deps: Dependencies): DownloadComponent {
            val app = deps.context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val flags = DataStoreFeatureFlags(app, deps.featureDefaultEnabled)
            val store = DataStoreRunStateStore(app, deps.clock)
            val monitor = DefaultDeviceStateMonitor(deps.battery, deps.network, deps.driving, deps.subscription)
            val notifications = NotificationController(app)
            val events = DefaultEventBus(listOf(Logger(app, debugToasts = deps.debug), notifications))
            val policy = DefaultPolicyEvaluator(logAllFailures = deps.debug) { Log.d(Logger.TAG, it) }
            val scheduler = Scheduler(WorkManager.getInstance(app), deps.config, flags, deps.clock)
            val coordinator = RunCoordinator(store, deps.config, flags, monitor, policy, deps.engine, scheduler, events, notifications, deps.clock)
            val watcher = WaitingWatcher(store, deps.config, monitor, policy, scheduler, events)
            return DownloadComponent(app, scope, deps.config, flags, store, monitor, events, notifications, scheduler, coordinator, watcher)
                .also { instance = it }
        }
    }
}

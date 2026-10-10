# Lucid App Store: download service

Android Automotive (AAOS) privileged app that downloads the app bundle once a day, but only while the
vehicle is safely parked, the EV battery is healthy and an allowed network is available. The download
itself is a black box (`DownloadEngine`); this phase builds the shell around it. Requirements:
[android-download-service-requirements-v3.md](android-download-service-requirements-v3.md).

Targets Android 12 and 15 (`minSdk 31`, `compileSdk/targetSdk 35`). Kotlin, coroutines, WorkManager,
DataStore, Compose. Gradle 9.4.1, AGP 9.1.1 (built-in Kotlin).

## Modules

| Module | Role | Depends on |
|---|---|---|
| `automotive` | App `com.lucid.appstore`: manifest, composition root, Compose status screen, AIDL service, catalogue stub | core, vehicle-signals |
| `download-core` | Scheduling, policy gate, run coordinator, waiting watcher, monitors, config, run state, events, notifications, feature flag, fake engine | WorkManager, DataStore |
| `vehicle-signals` | `CarPropertyManager` implementations of the two vehicle signal interfaces | core, `android.car` stubs |

`download-core` never touches car APIs, so it can be built and exercised without a vehicle.

### Replacing the vehicle signal sources

Implement these two interfaces from `download-core` and pass them to `DownloadComponent.Dependencies`
in `AppStoreApplication`:

```kotlin
interface DrivingStateProvider { val driving: Flow<DrivingState> }   // PARKED, IDLING, MOVING, UNKNOWN
interface BatteryMonitor       { val battery: Flow<BatteryState> }   // levelPercent, charging
```

Flows must be cold: register callbacks on collect and unregister on cancel. Use
`DrivingStateMapper.map(gearIsPark, speedMps, parkingBrakeOn, requireParkingBrake)` so the parked rule stays
in one place. Then drop the `vehicle-signals` module.

## Runtime flow

1. `Scheduler.ensureScheduled()` arms the unique `daily_trigger` work at the configured time (default 02:00).
2. `ScheduleTriggerWorker` fires, enqueues `download_run` if idle (else `SCHEDULE_COALESCED`), re-arms tomorrow.
3. `DownloadRunWorker` calls `RunCoordinator.execute()`: policy gate (driving, battery, network), then
   `setForeground(dataSync)` and the engine. Mid-run violations cancel the engine, persist `WAITING(reason)`
   and end the worker. A system stop is handled in a `NonCancellable` block.
4. While `WAITING`, `WaitingWatcher` (in process) applies the 10 s stability window and enqueues the run;
   a 15 min persisted recheck is the safety net.
5. Every state change goes through `EventBus` to the logcat `Logger` (tag `DlService`), the
   `NotificationController` and, in debug builds, a Toast.

Package layout in `download-core`: `model`, `policy`, `monitor`, `config`, `run`, `scheduling`, `events`, `engine`.

## Feature flag

`Flags.downloadServiceEnabled` is the kill switch. The default comes from
`BuildConfig.DOWNLOAD_SERVICE_ENABLED` in `automotive/build.gradle.kts`; the runtime value is stored in
DataStore and can be toggled from the status screen. Off: all work is cancelled, the run state is reset,
notifications are cleared and the AIDL status reports `enabled = false`. Swap `DataStoreFeatureFlags` for a
remote-config implementation of `FeatureFlags` when one exists.

## AIDL

`IAppStoreDownloadService` (action `com.lucid.appstore.IAppStoreDownloadService`) exposes
`getAvailableApps()`, `getStatus()`, `startDownload(packageName)`, `cancelDownload()` and a status callback.
Binding requires `com.lucid.appstore.permission.BIND_DOWNLOAD_SERVICE` (`signature|privileged`).
The catalogue comes from `CatalogueProvider`; `StubCatalogueProvider` returns one fixed entry until the
backend exists.

## Privileged install

Install under `/system/priv-app` or `/product/priv-app` and copy
`automotive/privapp-permissions-com.lucid.appstore.xml` to the matching `etc/permissions`.
Car permissions used: `CAR_POWERTRAIN`, `CAR_SPEED`, `CAR_ENERGY`, `CAR_ENERGY_PORTS`.
The status screen requests the two dangerous ones at runtime.

## Build and run

```bash
./gradlew :automotive:assembleDebug
```

Open the app on an AAOS image to see the status screen: run phase, vehicle signals, config editor,
feature flag, "Run now" and the event log. The `FakeDownloadEngine` simulates 600 MB over two minutes and
keeps its offset across pauses within the process.

## Deferred

The real `DownloadEngine`, retries, integrity, storage rule and failure notifications are the next phase
(requirements section 12). Unit tests are not written yet; `PolicyEvaluator`, `NetworkClassifier`,
`DrivingStateMapper` and `Scheduler.delayToNext` are pure and ready for them.

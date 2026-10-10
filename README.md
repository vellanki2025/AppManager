# Lucid App Store: download service

Android Automotive (AAOS) privileged app that downloads the app bundle once a day, but only while the
vehicle is safely parked, the EV battery is healthy and an allowed network is available. The download
itself is a black box (`DownloadEngine`); this phase builds the shell around it. Requirements:
[android-download-service-requirements-v3.md](android-download-service-requirements-v3.md).

Targets Android 12 and 15 (`minSdk 31`, `compileSdk/targetSdk 35`). Kotlin, coroutines, WorkManager,
DataStore, Compose, AIDL. Gradle 9.4.1, AGP 9.1.1 (built-in Kotlin).

## Layout

Single app module `automotive`, package `com.lucid.appstore`:

| Package | Role |
|---|---|
| `download.*` | Scheduling, policy gate, run coordinator, waiting watcher, monitors, config, run state, events, notifications, feature flag, fake engine. No vehicle API dependency. |
| `signals` | Adapters from the vehicle signal library to the two core interfaces. The only place that touches vehicle APIs. |
| `ipc` | `AppStoreDownloadService`, the AIDL surface used by the Compose UI and by other store clients |
| `ui` | Compose status screen; talks to the service only through AIDL |
| `catalogue` | `CatalogueProvider` with a stub until the backend exists |

### Connecting the vehicle signal library

Fill in the two `TODO(signal-lib)` blocks in
[VehicleSignals.kt](automotive/src/main/java/com/lucid/appstore/signals/VehicleSignals.kt):

```kotlin
interface DrivingStateProvider { val driving: Flow<DrivingState> }   // PARKED, IDLING, MOVING, UNKNOWN
interface BatteryMonitor       { val battery: Flow<BatteryState> }   // levelPercent, charging
```

Subscribe in the `callbackFlow` body, `trySend` on every update, unsubscribe in `awaitClose`. The driving
adapter already maps gear, speed and parking brake through `DrivingStateMapper`. Until connected the adapters
emit fail-safe values, so the gate blocks on `DRIVING`. Add the library's permissions to its own manifest.

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

Sub-packages of `download`: `model`, `policy`, `monitor`, `config`, `run`, `scheduling`, `events`, `engine`.

## Feature flag

`Flags.downloadServiceEnabled` is the kill switch. The default comes from
`BuildConfig.DOWNLOAD_SERVICE_ENABLED` in `automotive/build.gradle.kts`; the runtime value is stored in
DataStore and can be toggled from the status screen. Off: all work is cancelled, the run state is reset,
notifications are cleared and the AIDL status reports `enabled = false`. Swap `DataStoreFeatureFlags` for a
remote-config implementation of `FeatureFlags` when one exists.

## AIDL

`IAppStoreDownloadService` (action `com.lucid.appstore.IAppStoreDownloadService`) is the only way into the
service, for the built-in UI as well as external clients:

- `getAvailableApps()`, `getStatus()`, `getConfig()`, `setConfig()`, `setEnabled()` (feature flag),
  `startDownload(packageName)`, `cancelDownload()`
- `IDownloadStatusCallback`: `onStatusChanged(DownloadStatus)` on every run-state, flag or vehicle-signal
  change, `onEvent(String)` with one formatted log line per service event

Binding requires `com.lucid.appstore.permission.BIND_DOWNLOAD_SERVICE` (`signature|privileged`).
Vehicle signals are observed only while a client is bound.

## Privileged install

Install under `/system/priv-app` or `/product/priv-app`. If the signal library needs
`signature|privileged` car permissions, add a `privapp-permissions-com.lucid.appstore.xml` allowlist to
`etc/permissions`.

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

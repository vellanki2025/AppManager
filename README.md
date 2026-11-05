# Lucid App Manager: download service

Android Automotive (AAOS) privileged app that downloads the app bundle once a day, but only while the
vehicle is safely parked, the EV battery is healthy and an allowed network is available, then verifies and
installs it.

Requirements: [v3](android-download-service-requirements-v3.md) (the shell) and
[v4](android-download-service-requirements-v4.md) (cloud contract, real transfer, install).
Design: [docs/design-v4-install-and-engine.md](docs/design-v4-install-and-engine.md).

Targets Android 12 and 15 (`minSdk 31`, `compileSdk/targetSdk 35`). Kotlin, coroutines, WorkManager,
DataStore, Compose, AIDL. Gradle 9.4.1, AGP 9.1.1 (built-in Kotlin).

## Layout

Single app module `automotive`, package `com.lucid.appmanager`:

| Package | Role |
|---|---|
| `download.*` | Scheduling, policy gate, run coordinator, waiting watcher, monitors, config, run state, events, notifications, feature flag, fake engine. No vehicle API dependency. |
| `signals` | Adapters from the vehicle signal library to the two core interfaces. The only place that touches vehicle APIs. |
| `ipc` | `AppManagerDownloadService`, the AIDL surface used by the Compose UI and by other store clients |
| `ui` | Compose status screen; talks to the service only through AIDL |
| `catalogue` | `Artifact`, `CatalogueProvider`, `ArtifactUrlProvider`, `ArtifactResolver`. Stubs until the backend exists |
| `install` | Both install paths, the pending-override record and the non-exported install result receiver |
| `download.verify` | `IntegrityVerifier`: length, APK parse, digest, signing certificate |
| `debug` | **Debug builds only.** `DebugFlagActions`, its adb receiver and the decorators they drive |

### Connecting the vehicle signal library

Fill in the `TODO(signal-lib)` blocks in
`VehicleDrivingStateProvider.kt` and `VehicleBatteryMonitor.kt` under [signals/](automotive/src/main/java/com/lucid/appmanager/signals/):

```kotlin
interface DrivingStateProvider             { val driving: Flow<DrivingState> }   // PARKED, IDLING, MOVING, UNKNOWN
interface BatteryMonitor                   { val battery: Flow<BatteryState> }   // levelPercent, charging
interface ConnectivitySubscriptionProvider { val subscribed: Flow<Boolean> }     // vehicle data plan active
```

Subscribe in the `callbackFlow` body, `trySend` on every update, unsubscribe in `awaitClose`. The driving
adapter already maps gear and speed through `DrivingStateMapper`. Until connected the adapters
emit fail-safe values, so the gate blocks on `DRIVING`. Add the library's permissions to its own manifest.

## Runtime flow

1. `Scheduler.ensureScheduled()` arms the unique `daily_trigger` work at the configured time (default 02:00).
2. `ScheduleTriggerWorker` fires, enqueues `download_run` if idle (else `SCHEDULE_COALESCED`), re-arms tomorrow.
3. `DownloadRunWorker` calls `RunCoordinator.execute()`: policy gate (driving, battery, network, storage;
   metered hotspots are always blocked), then

   ```
   ① resolve artifact → ② mint URL → ③ transfer → ④ verify → gate again → ⑤ install
   ```

   Mid-run violations cancel the engine, persist `WAITING(reason)` and end the worker. A system stop is
   handled in a `NonCancellable` block. A run that already has a verified APK on disk skips ①–④ and goes
   straight to install, so a driving-blocked install does not re-download hundreds of megabytes when the
   vehicle parks.
4. While `WAITING`, `WaitingWatcher` (in process) applies the 10 s stability window and enqueues the run;
   a 15 min persisted recheck is the safety net.
5. Every state change goes through `EventBus` to the logcat `Logger` (tag `DlService`), the
   `NotificationController` and, in debug builds, a Toast.

Sub-packages of `download`: `model`, `policy`, `monitor`, `config`, `run`, `scheduling`, `events`, `engine`.

## Feature flag

`Flags.downloadServiceEnabled` is the kill switch. The default comes from
`BuildConfig.DOWNLOAD_SERVICE_ENABLED` in `automotive/build.gradle.kts`; the runtime value is stored in
DataStore and can be toggled from the status screen. Off: all work is cancelled, the run state is reset,
notifications are cleared and the AIDL status reports `enabled = false`.

`Flags.installMode` picks the install path — see [Install](#install). Default from
`BuildConfig.INSTALL_MODE`, same DataStore, same toggle.

Swap `DataStoreFeatureFlags` for a remote-config implementation of `FeatureFlags` when one exists.

## Install

Two paths behind one flag (`Flags.installMode`, default `PACKAGE_MANAGER`, toggleable on the status
screen and from `DebugFlagActions`). The path is read when install begins, so flipping it mid-run takes
effect on the next run.

| | `PACKAGE_MANAGER` | `APK_OVERRIDE` |
|---|---|---|
| Who installs | the app, in-process | a human or script, via adb |
| Takes effect | immediately | next reboot |
| Needs | `INSTALL_PACKAGES` (privileged) | writable partition: userdebug, verity off |
| Works on a production image | yes | **no** |
| App's job | download, verify, install | download, verify, **stage and report** |

`APK_OVERRIDE` never writes to a partition and never reboots the vehicle. It copies the verified APK to
`filesDir/override/<package>.apk`, records what it is waiting for, and logs the adb sequence:

```bash
adb root && adb remount
adb push <stagedPath> /product/priv-app/<App>/<App>.apk
adb shell chmod 644 /product/priv-app/<App>/<App>.apk
adb reboot
```

At the next service start the record is checked against the installed `versionCode` and reported as
`OVERRIDE_APPLIED` or `OVERRIDE_NOT_APPLIED`. The boot counter stored with the record is what separates
"not rebooted yet" from "rebooted and it did not land".

Whatever the installer returns, the installed `versionCode` is re-read afterwards: the platform's answer
is advisory, the device is authoritative.

## Cloud contract

Two calls, deliberately. The artifact says *what* to install and is cached across a wait; the URL is
minted separately, lives 30 minutes, and is fetched only once the policy gate has opened and the transfer
is about to begin. A run routinely waits hours, so a URL fetched any earlier would be dead on arrival.

An expiry mid-transfer is routine, not a failure: the engine re-mints and resumes from its offset
(`URL_REFRESHED`). 401 and 403 are read as expiry and trigger a refresh *before* the retry ladder sees
them. Re-fetches are bounded per run, so an endlessly-rejecting server fails rather than loops.

Both interfaces are our proposal and no backend has reviewed them (requirements A20).

## Debug actions

**Debug builds only.** `DebugFlagActions` is in `src/debug`; nothing in `src/main` names it, and neither
it nor its receiver exists in a release APK. The debug manifest swaps the Application for
`DebugApplication`, which decorates the real dependencies — that is the only seam.

`DebugActionReceiver` is exported but gated on `android.permission.DUMP`. That is stricter than it
sounds, and it is deliberate: a **non-exported** receiver cannot be reached by adb at all — `am broadcast`
runs as the shell uid and the platform drops a cross-uid delivery silently, printing `result=0` for
"enqueued" either way. `DUMP` is `signature|privileged`, held by the shell and the system and not
grantable to a normal app, so adb works and no other app can drive this app's installer.

```bash
adb shell am broadcast -n com.lucid.appmanager/.debug.DebugActionReceiver \
    -a com.lucid.appmanager.debug.<ACTION> [--es|--ei|--ez|--el <key> <value>]
adb logcat -s DlService          # replies and events land here, tagged [debug]
```

### Flags

| Use case | Action | API |
|---|---|---|
| Kill switch on/off | `SET_SERVICE_ENABLED --ez enabled true` | `setServiceEnabled` |
| Choose install path | `SET_INSTALL_MODE --es mode APK_OVERRIDE` | `setInstallMode` |
| Read current flags | `DUMP_FLAGS` | `dumpFlags` |

### Schedule

| Use case | Action | API |
|---|---|---|
| Run now, skip the 02:00 wait | `TRIGGER_NOW` | `triggerNow` |
| Move the daily time | `SET_SCHEDULE_TIME --es at 02:00` | `setScheduleTime` |
| Cancel the run and drop a staged APK | `CANCEL_RUN` | `cancelRun` |

### Policy gate — forcing each block reason

| Use case | Action | API |
|---|---|---|
| Driving blocks start, pauses a transfer, blocks install | `SET_DRIVING --es state MOVING` | `setDrivingState` |
| Battery threshold and resume margin | `SET_BATTERY --ei level 15 --ez charging false` | `setBattery` |
| Network class; metered hotspots always refused | `SET_NETWORK --es class CELLULAR` | `setNetworkClass` |
| Cellular without a subscription | `SET_CELLULAR_SUBSCRIBED --ez subscribed false` | `setCellularSubscribed` |
| Storage below size plus margin | `SET_FREE_SPACE --ei mb 100` | `setFreeSpaceMb` |
| Back to the real vehicle signals | `CLEAR_SIGNAL_OVERRIDES` | `clearSignalOverrides` |

### Cloud and URL

| Use case | Action | API |
|---|---|---|
| Inject the artifact to install | `SET_ARTIFACT --es pkg com.x --el code 42 [--el size N] [--es digest <hex>]` | `setArtifact` |
| Prove the up-to-date short-circuit | `SET_ARTIFACT` with the installed `code` | — |
| Back to the real catalogue | `CLEAR_ARTIFACT` | `clearArtifact` |
| Transparent mid-transfer refresh | `EXPIRE_URL_NOW` | `expireUrlNow` |
| Exhaust the refresh bound | `FAIL_URL_FETCH --ei times 4` | `failUrlFetch` |
| Shorten the TTL so expiry lands mid-transfer | `SET_URL_TTL --ei seconds 30` | `setUrlTtlSeconds` |

### Transfer

| Use case | Action | API |
|---|---|---|
| Server without `Range` support | `SET_RANGE_SUPPORT --ez supported false` | `setRangeSupport` |
| Force one HTTP status (404 is fatal, 5xx retries) | `FORCE_HTTP_STATUS --ei code 503` | `forceHttpStatus` |
| Walk the retry ladder | `FORCE_TRANSIENT_FAILURES --ei times 3` | `forceTransientFailures` |
| Slow it down to watch pause and resume | `SET_SPEED --ei kbps 500` | `setSpeedKbps` |

### Integrity

| Use case | Action | API |
|---|---|---|
| Digest mismatch, then one retry from scratch | `CORRUPT_NEXT_DOWNLOAD` | `corruptNextDownload` |
| Length mismatch caught before hashing | `TRUNCATE_NEXT_DOWNLOAD --ei bytes 1024` | `truncateNextDownload` |
| Signature mismatch (use a differently-signed APK) | `USE_APK --es path /data/local/tmp/other.apk` | `useLocalApk` |

### Install — `PACKAGE_MANAGER`

| Use case | Action | API |
|---|---|---|
| Install a local APK now, skipping the transfer | `INSTALL_NOW --es path /data/local/tmp/app.apk` | `installNow` |
| Force an install failure | `FORCE_INSTALL_FAILURE --ei code 4` | `forceInstallFailure` |
| Prove install is driving-gated | `SET_DRIVING --es state MOVING` then `INSTALL_NOW` | — |

### Install — `APK_OVERRIDE`

| Use case | Action | API |
|---|---|---|
| Stage an override | `STAGE_OVERRIDE --es path /data/local/tmp/app.apk --es pkg com.x --el code 42` | `stageOverride` |
| Print the adb sequence to apply it | `PRINT_OVERRIDE_COMMANDS` | `printOverrideCommands` |
| Inspect the pending record | `DUMP_PENDING_OVERRIDE` | `dumpPendingOverride` |
| Re-run the post-reboot check | `CHECK_OVERRIDE_APPLIED` | `checkOverrideApplied` |
| Clear it | `CLEAR_PENDING_OVERRIDE` | `clearPendingOverride` |

### State

| Use case | Action | API |
|---|---|---|
| Run state, config, flags and every override | `DUMP_STATE` | `dumpState` |
| Clear everything and cancel the run | `RESET_ALL` | `resetAll` |


## AIDL

`IAppManagerDownloadService` (action `com.lucid.appmanager.IAppManagerDownloadService`) is the only way into the
service, for the built-in UI as well as external clients:

- `getAvailableApps()`, `getStatus()`, `getConfig()`, `setConfig()`, `setEnabled()` (feature flag),
  `startDownload(packageName)`, `cancelDownload()`
- `IDownloadStatusCallback`: `onStatusChanged(DownloadStatus)` on every run-state, flag or vehicle-signal
  change, `onEvent(String)` with one formatted log line per service event

Binding requires `com.lucid.appmanager.permission.BIND_DOWNLOAD_SERVICE` (`signature|privileged`).
Vehicle signals are observed only while a client is bound.

## Package visibility

The app holds `QUERY_ALL_PACKAGES`, and it is required rather than optional: the catalogue is
server-driven, so the packages to inspect are unknown at build time and a static `<queries>` list would
cap the catalogue at whatever was known when the APK shipped.

Without it `getPackageInfo` throws `NameNotFoundException` for every other package and three things break
**silently** instead of failing: the up-to-date short-circuit never fires so every run re-downloads, the
APK signature check treats every app as a first install and is skipped entirely, and an install cannot be
confirmed by re-reading the `versionCode`.

## Privileged install

Install under `/system/priv-app` or `/product/priv-app`. If the signal library needs
`signature|privileged` car permissions, add a `privapp-permissions-com.lucid.appmanager.xml` allowlist to
`etc/permissions`.

## Build and run

```bash
./gradlew :automotive:assembleDebug
```

Open the app on an AAOS image to see the status screen: run phase, vehicle signals, config editor,
feature flag, "Run now" and the event log. The `FakeDownloadEngine` simulates 600 MB over two minutes and
keeps its offset across pauses within the process.

## Decisions

- **Driving blocks both download and install** (2026-10-12). The download gate keeps the driving rule as its
  first check and pauses a running transfer when the vehicle leaves PARKED. The install phase applies the same
  rule. This supersedes the "downloads are not tied to vehicle state" assumption in
  `vehicle-updater-service-flow.html`.
- **Cellular is a Settings choice, never a prompt** (2026-10-12). The "Wi-Fi or cellular" option is shown
  only while the vehicle has a connectivity subscription; otherwise the setting is hidden and the policy
  treats the vehicle as Wi-Fi only even if the stored value says cellular. Applies to scheduled and manual
  downloads alike.

## Deferred

The real `DownloadEngine`, retries, integrity, storage rule and failure notifications are the next phase
(requirements section 12). See [docs/download-engine-comparison.md](docs/download-engine-comparison.md)
for Android `DownloadManager` versus our own transfer as that engine. Unit tests are not written yet; `PolicyEvaluator`, `NetworkClassifier`,
`DrivingStateMapper` and `Scheduler.delayToNext` are pure and ready for them.

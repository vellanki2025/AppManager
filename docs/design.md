# LucidAppManager — download and install service

One document for the on-vehicle service: what it does, how a run unfolds, why the awkward parts are the
way they are, and how to drive every case from a bench.

Supersedes the v3 and v4 requirements, the v4 design note and the download-engine comparison, all four of
which were removed on 2026-11-05 — they recorded how the design arrived, this records what it is. Their
conclusions are kept in [§12](#12-decisions-and-why); the full text is in git history.

An interactive walkthrough of the same pipeline lives in [docs/event-flow.html](event-flow.html) — step
through a run, change the vehicle signals, watch it branch.

---

## 1. Scope

A privileged Android Automotive app that keeps a small set of vehicle apps up to date, unattended,
overnight, without ever interrupting the driver.

**In scope.** A daily schedule; a policy gate on driving, battery, network and storage; checking what to
install against what is installed; fetching a short-lived APK URL; downloading with resume; verifying
integrity and provenance; installing by one of two paths; surviving process death, reboot and system stop.

**Out of scope, deliberately.** Uninstall, downgrade and rollback. Split APKs and APEX. More than one
manifest in flight at a time — a new one supersedes the current one rather than queueing beside it. Self-update — this APK ships in the system image by OTA and never updates itself.
Unit tests, per the standing project decision.

| | |
|---|---|
| Module | single app module `automotive`, package `com.lucid.appmanager` |
| Toolchain | Kotlin, Gradle 9.4.1 / AGP 9.1.1, minSdk 31, compile and target 35 |
| Entry point | AIDL only — `IAppManagerDownloadService` |
| Scheduling | WorkManager, two unique work names |
| Persistence | DataStore Preferences |

---

## 2. Layout

```
com.lucid.appmanager
├── catalogue/        Manifest, SignedUrl, CatalogueProvider, ManifestChecker
├── download/
│   ├── config/       Config, FeatureFlags, ConfigRepository
│   ├── engine/       DownloadEngine, HttpDownloadEngine, UrlSession
│   ├── events/       EventType, ServiceEvent, EventBus, NotificationController, Logger
│   ├── model/        DeviceState, Config, RunState, PolicyDecision, enums
│   ├── monitor/      DeviceStateMonitor, ConnectivityNetworkMonitor
│   ├── policy/       PolicyEvaluator, NetworkClassifier, DrivingStateMapper
│   ├── run/          RunCoordinator, RunStateStore, DownloadRunWorker, CancelReceiver
│   ├── scheduling/   Scheduler, ScheduleTriggerWorker
│   └── verify/       IntegrityVerifier
├── install/          Installer, PackageManagerInstaller, OverrideStagingInstaller, PendingOverride
├── ipc/              AppManagerDownloadService (AIDL binder)
├── signals/          vehicle driving / battery / connectivity adapters
└── ui/               MainActivity, StatusScreen, StatusViewModel
```

`src/debug/` adds `DebugApplication`, `DebugFlagActions` and `DebugActionReceiver`. Nothing in `src/main`
names them — see [§11](#11-debug-actions).

---

## 3. The run

One invocation of `download_run`:

```
gate → ① manifest check → for each app that needs it:
                              storage gate → ② fetch APK URL → ③ download → ④ verify → gate → ⑤ install
```

The manifest check runs **once**, for the whole run. Stages ②–⑤ then repeat per app, **serially**: one
engine, one `.part`, one foreground notification, and a peak disk cost of the largest single app rather
than the whole release. Each app's APK is deleted as soon as it is installed, before the next download
starts.

An app that already has a verified APK on disk skips ②–④ and re-enters at its install gate.

| Step | Does | Ends the run when |
|---|---|---|
| Trigger | `daily_trigger` fires, enqueues `download_run` | the kill switch is off |
| **Gate** | driving, battery, network | any rule fails → `WAITING`, recheck in 15 min |
| **① Manifest check** | one catalogue call; each app's `versionCode` vs installed | every app already current → `UP_TO_DATE` each, zero bytes |
| **Storage gate** | *per app*: that app's size + 10% must fit | → `WAITING` |
| **② Fetch URL** | mint a signed URL, 30 min TTL | the backend refuses |
| **③ Download** | `Range` resume into `.part`, atomic rename | 404, or the retry ladder is exhausted |
| **④ Verify** | length → APK parse → SHA-256 → signer | two failures in a row |
| **Install gate** | driving, battery **only** | → `WAITING`, APK kept on disk |
| **⑤ Install** | session commit, or stage an override | the install fails or needs user action |
| Complete | clear state, re-arm the trigger | always `DOWNLOAD_COMPLETE` |

**A failed app does not fail the run.** One bad APK on the server costs that app its update and nothing
else; the loop records it and moves on, and `DOWNLOAD_COMPLETE` closes with a summary — "1 of 2 installed,
1 failed". A *block* is different: it suspends the whole run, which simply returns with the reason
persisted, and the recheck recomputes the plan from the device. An app installed before the block drops
out of `updates` on its own, so the queue never needs persisting.

Three more properties that are easy to miss:

- **The gate runs twice, from two different snapshots.** A large APK on a slow link takes hours; the
  decision taken before the download is stale by install time.
- **The URL is fetched as late as possible.** It lives 30 minutes and a run can sit blocked for hours.
- **Downloading and installing are separate halves.** A blocked install keeps its verified APK, so parking
  the car later installs what is already there rather than fetching it again.

---

## 4. Policy gate

Four rules, fixed order, **first failure wins**. Blocked means the run persists `WAITING` with the reason
and schedules a recheck 15 minutes out. A recheck that finds the same reason logs `RECHECK_STILL_BLOCKED`
rather than repeating the `BLOCKED_*` event, so a long wait does not flood the log.

| Rule | Passes when |
|---|---|
| Driving | stationary: `PARKED`, `ACCESSORY`, `STOPPED` or `TURNED_OFF`. `DRIVING` blocks; `UNKNOWN` blocks too, because an unread signal is not a parked car |
| Battery | ≥ 20%, or charging at any level. Resuming after a battery block needs 20 + 5 = **25%** |
| Network | validated unmetered Wi-Fi, or the vehicle's own cellular with `WIFI_OR_CELLULAR` **and** a connectivity subscription. Metered Wi-Fi, ethernet and none always refuse |
| Storage | free space ≥ manifest size + 10%. Unknown free space (`-1`) never blocks |

Charging below the threshold still proceeds, and logs `CHARGING_OVERRIDE`.

**Coming back from a block**, the gate must stay open for a continuous **10 seconds** before the run
proceeds. A signal that flaps — a car rolling a metre in a parking space — re-blocks rather than starting
a download it is about to have to pause.

**The install gate is narrower**: driving and battery only. Storage is skipped (`requiredBytes = 0`) and so
is the network, because installing reads a verified APK off local storage and moves no bytes over the wire.
A car that finished downloading and then lost Wi-Fi can still install what it holds.

**Mid-download the gate is continuous.** A watcher coroutine re-evaluates it on every device-state change
and cancels the engine if any rule fails; the `.part` file survives and the next attempt resumes from the
saved offset.

---

## 5. Cloud contract

Two calls, deliberately. The catalogue describes *what* to install; a second call produces *where* to get
it. A 30-minute URL cannot live inside a long-lived manifest.

```kotlin
Manifest(manifestId, apps: List<ManifestApp>)
ManifestApp(packageName, title, versionName, versionCode, sizeBytes, digest)
SignedUrl(url, expiresAt)

CatalogueProvider.currentManifest(): Manifest?
ApkUrlProvider.downloadUrl(manifestId, packageName): SignedUrl
```

**A manifest is a release: one id, several apps.** It is the unit that arrives and gets replaced, which is
why only one is ever in flight — a different `manifestId` supersedes the previous one rather than queueing
beside it. The apps inside it are what the run iterates.

`ManifestChecker.check()` runs **once per run** and produces the plan: every app whose `versionCode` beats
what is installed goes in `updates`, the rest in `upToDate`. An app the vehicle does not have at all
counts as installed version 0, so a manifest can add an app as well as move one forward. Apps in
`upToDate` are reported once and never enter the loop, so they never cost a byte.

Comparison is on `versionCode`, never `versionName` — that one is a free-form string, and sorting it as
text puts "1.10.0" below "1.9.0".

`UrlSession` owns the URL for the life of a run. It re-fetches when under **60 seconds** remain, bounded at
**3** re-fetches per run, past which the run fails rather than hammering a rejecting server. A `401` or
`403` is read as expiry, not as an authorization failure: against a 30-minute signature that is
overwhelmingly what it means.

Reaching the catalogue is itself a network call. A failure — dropped link, DNS, timeout, backend down — is
handled as a `NETWORK` block carrying the cause, so the run waits and rechecks.

---

## 6. Download engine

`HttpDownloadEngine` over `HttpURLConnection`.

| | |
|---|---|
| Target | `<packageName>.apk.part`, renamed atomically on success |
| Resume | `Range` + `If-Range`; the returned `Content-Range` is validated |
| Progress | at most one `DOWNLOAD_PROGRESS` per second |
| Attempts | 5, backing off 30 s → 15 min |
| Fatal | `404` |
| Retryable | `5xx`, `429`, I/O |
| Expiry | `401` / `403` → re-fetch the URL, then retry |

A server that ignores the range restarts from zero rather than appending to the wrong offset. The
coordinator sees one of three outcomes — `DownloadOutcome.Done`, `.Paused`, `.Failed` — distinct from the
engine's own `DownloadResult`, because only the coordinator can tell a closing gate from an error.

---

## 7. Integrity

Four checks, cheapest first, so a truncated file costs a `length()` call rather than hashing several
hundred megabytes.

| Order | Check | Failure |
|---|---|---|
| 1 | byte length against `sizeBytes` | `LENGTH` |
| 2 | parses as an APK | `NOT_AN_APK` |
| 3 | SHA-256 against `digest` | `DIGEST` |
| 4 | signing certificate matches the installed copy | `SIGNATURE` |

**The signer check is the one that matters.** A digest only proves the bytes arrived intact; it says
nothing about who built them, and whoever can serve you an APK can serve you its hash. The first install of
a package has nothing to compare against — allowed, and logged. It is the one hole.

Any failure deletes the file and retries the whole download **once**. A second failure ends the run as
`DOWNLOAD_FAILED: verification failed twice` — two independent failures mean the source is wrong, not the
wire.

---

## 8. Install

Which path runs is a feature flag, carried on `DownloadStatus` so the UI can show it, and named in the
`INSTALL_STARTED` message.

### Path A — `PACKAGE_MANAGER` (default)

A `PackageInstaller` session: write `base.apk`, commit with a `PendingIntent` aimed at
`InstallResultReceiver`. The platform's `INSTALL_SUCCEEDED` is treated as **advisory** — the run re-reads
the installed `versionCode` and only then reports success. A reported success with an unchanged version is
a failure.

`INSTALL_NEEDS_USER_ACTION` means `USER_ACTION_NOT_REQUIRED` was refused, which in practice means the app
is not running privileged. On a bench emulator that is the expected result, not a bug.

### Path B — `APK_OVERRIDE`

Installs nothing. Copies the APK to `<filesDir>/override/<packageName>.apk`, records a `PendingOverride`
with the current `BOOT_COUNT`, and ends at `OVERRIDE_STAGED`. Replacing a preloaded APK needs root and
verity off, so the apply is an adb sequence on a userdebug build, effective at the next reboot:

```bash
adb root && adb remount
adb push <staged>.apk /product/priv-app/<Name>/<Name>.apk
adb shell chmod 644 /product/priv-app/<Name>/<Name>.apk
adb reboot
```

After the reboot, `BOOT_COUNT` is compared against the staged value to distinguish `OVERRIDE_APPLIED` from
`OVERRIDE_NOT_APPLIED` — "rebooted and it took" versus "never rebooted". The partition directory is
derived from the last segment of the package name, so **check it against the real image** before trusting
the printed path.

| | Path A | Path B |
|---|---|---|
| Needs privileged install | yes | no |
| Needs root / verity off | no | yes |
| Effective | immediately | next reboot |
| Production answer | yes | bench only |

---

## 9. State, resume and cancel

```kotlin
RunState(runId, phase, manifestId, apkPath, waitReason, hasStarted, progress,
         lastStateChangeAt, lastSuccessAt)
```

`phase` is `IDLE`, `RUNNING`, `WAITING` or `INSTALLING`. Written through `RunStateStore` on every
transition, so a process death mid-run is restored and logged as `STATE_RESTORED`.

**`apkPath` and `manifestId` are a pair.** A staged APK is reused only when `manifestId` matches the
manifest the current run just checked. The catalogue can publish a newer version while a run sits blocked;
without that guard the run installs the older staged APK.

**One manifest at a time: when a new one arrives, the old one's bytes go.** On a mismatch the run deletes
everything in the download directory — the verified APK *and* any partial `.part` — clears `apkPath`,
resets the started flag so the next transfer reports `DOWNLOAD_STARTED` rather than `RESUMED`, and emits
`RESTARTED_FROM_ZERO` naming what it dropped.

The `.part` half matters as much as the APK. It is named after the package, not the version, and the
`If-Range` validator lives on the engine instance, which is rebuilt per run — so across runs there is no
validator and the server cannot police the resume. Without the sweep, a catalogue change during a pause
would append new-version bytes onto old ones. The digest catches it, but only after a wasted transfer and
one of just two verify attempts, so a genuinely transient second failure would then fail the run.

Anything else in the directory is swept on the same principle: with one manifest in flight there is
nothing else it could belong to.

Three ways a run ends abnormally, kept distinguishable on purpose:

| | Event | Effect |
|---|---|---|
| User cancel | `CANCELLED` | drops the partial file **and** any verified-but-uninstalled APK |
| WorkManager stop | `WORK_STOPPED_BY_SYSTEM` | keeps progress, rechecks immediately |
| Kill switch | `SERVICE_DISABLED` | the run never starts |

In all three `daily_trigger` stays armed.

---

## 10. Events

36 types on one bus (`EventBus`), fanned out to logcat under tag `DlService`, to the notification
controller, and to AIDL clients as a formatted line.

| Group | Types |
|---|---|
| Schedule | `SCHEDULE_FIRED` `SCHEDULE_COALESCED` |
| Gate | `BLOCKED_DRIVING` `BLOCKED_BATTERY` `BLOCKED_NETWORK` `BLOCKED_STORAGE` `CHARGING_OVERRIDE` `RECHECK_STILL_BLOCKED` |
| Cloud | `UP_TO_DATE` `URL_FETCHED` `URL_REFRESHED` `URL_REFRESH_EXHAUSTED` |
| Download | `DOWNLOAD_STARTED` `DOWNLOAD_PROGRESS` `RESUMED` `PAUSED_DRIVING` `PAUSED_BATTERY` `PAUSED_NETWORK` `RETRY_SCHEDULED` `RESTARTED_FROM_ZERO` `DOWNLOAD_FAILED` |
| Integrity | `VERIFY_FAILED` |
| Install | `INSTALL_STARTED` `INSTALL_COMPLETE` `INSTALL_FAILED` `INSTALL_NEEDS_USER_ACTION` `OVERRIDE_STAGED` `OVERRIDE_APPLIED` `OVERRIDE_NOT_APPLIED` |
| Lifecycle | `STATE_RESTORED` `WORK_STOPPED_BY_SYSTEM` `CONFIG_CHANGED` `DEVICE_STATE_CHANGED` `DOWNLOAD_COMPLETE` `CANCELLED` `SERVICE_DISABLED` |

`DOWNLOAD_COMPLETE` always closes a successful run, whatever the terminal event was.

The **UI shows only four fields** per row — icon, title, version, state — plus a fixed three-word status
vocabulary. A failure reason never reaches a row.

---

## 11. Debug actions

**Debug builds only.** `DebugFlagActions` lives in `src/debug`; nothing in `src/main` names it. The debug
manifest swaps the Application for `DebugApplication`, which decorates the real dependencies — that is the
only seam, and a release APK contains none of it.

```bash
B() { adb shell am broadcast -n com.lucid.appmanager/.debug.DebugActionReceiver \
      -a com.lucid.appmanager.debug.$1 "${@:2}"; }
adb logcat -s DlService
```

| Group | Actions |
|---|---|
| Flags | `SET_SERVICE_ENABLED` `SET_INSTALL_MODE` `DUMP_FLAGS` |
| Schedule | `TRIGGER_NOW` `SET_SCHEDULE_TIME` `CANCEL_RUN` |
| Gate | `SET_NETWORK_TYPE` `SET_DRIVING` `SET_BATTERY` `SET_NETWORK` `SET_CELLULAR_SUBSCRIBED` `SET_FREE_SPACE` `CLEAR_SIGNAL_OVERRIDES` |
| Cloud | `SET_MANIFEST` `ADD_APP` `CLEAR_MANIFEST` `EXPIRE_URL_NOW` `FAIL_URL_FETCH` `SET_URL_TTL` |
| Download | `SET_RANGE_SUPPORT` `FORCE_HTTP_STATUS` `FORCE_TRANSIENT_FAILURES` `SET_SPEED` |
| Integrity | `CORRUPT_NEXT_DOWNLOAD` `TRUNCATE_NEXT_DOWNLOAD` `USE_APK` |
| Install A | `INSTALL_NOW` `FORCE_INSTALL_FAILURE` |
| Install B | `STAGE_OVERRIDE` `PRINT_OVERRIDE_COMMANDS` `DUMP_PENDING_OVERRIDE` `CHECK_OVERRIDE_APPLIED` `CLEAR_PENDING_OVERRIDE` |
| State | `DUMP_STATE` `RESET_ALL` |

`ADD_APP` appends an app to the injected manifest, which is how a multi-app release is exercised on a
bench: set a download fault between steps to fail exactly one app and watch the others still install.
Every edit mints a fresh `manifestId`, so each one looks like a new release arriving and triggers the
sweep.

`SET_NETWORK_TYPE` sets class, subscription and mode together as one coherent situation —
`UNMETERED_WIFI`, `METERED_WIFI`, `CELLULAR_ENABLED`, `CELLULAR_NO_SUBSCRIPTION`, `NONE` — which is easier
to reason about than three knobs with combinations that cannot occur.

To test cellular refused because the Settings option is off, rather than because of the subscription, set
the class and the mode separately: `SET_NETWORK --es class CELLULAR` with `networkMode` left at
`WIFI_ONLY`.

Full argument list: the KDoc on each `DebugFlagActions` function, and the table in the README.

**Two bench traps that are not bugs.** `WORK_STOPPED_BY_SYSTEM` immediately means the app is in the
background, so the `dataSync` foreground service cannot start — launch `MainActivity` first; a privileged
app on a real head unit is exempt. And signals reading `UNKNOWN` on a phone emulator means there are no car
signals, not that the gate is broken.

---

## 12. Decisions and why

**Own downloader inside WorkManager, not `DownloadManager`.** The platform provider is attractive — no
foreground service, no six-hour limit — but it rests on two things we cannot assume on the target image:
that `providers.downloads` is present at all, and that a privileged app can pause and resume by writing the
download row's `control` column. That pause is undocumented. Without a working pause every drive restarts a
500 MB download from zero, which decides it. The shell is unchanged either way: the coordinator cancels a
coroutine to pause, calls `download()` again to resume, `discard()` on cancel.

**WorkManager rather than a thread or a persistent service.** Replacing it would mean rebuilding the
persisted daily trigger, restore-after-reboot and re-run-after-process-kill on `JobScheduler` and
`AlarmManager`. `android:persistent="true"` avoids the kill cases but still provides no schedule and costs
memory on the head unit.

**`QUERY_ALL_PACKAGES` is required, and its absence fails silently.** On API 30+ without it
`getPackageInfo` throws for every other package, so the up-to-date check never fires, the post-install
confirmation cannot run, and — the real problem — the signature check treats every app as a first install
and is skipped entirely.

**The debug receiver is exported, gated on `android.permission.DUMP`.** A non-exported receiver cannot be
reached by adb at all: `am broadcast` runs as the shell uid and the platform drops the cross-uid delivery
silently, printing `result=0` either way, because that only means "enqueued". `DUMP` is
`signature|privileged`, held by the shell and the system and not grantable to a normal app — so adb works
and no other app can drive this app's installer.

**The vehicle signal adapters are stubs.** `signals/` has the shape; the real vehicle library is wired in
behind it.

### Defaults

| | |
|---|---|
| Schedule | 02:00 daily |
| Recheck interval | 15 min |
| Resume stability window | 10 s |
| Battery threshold / resume margin | 20% / +5% |
| Storage margin | 10% |
| Digest | SHA-256 |
| URL TTL / refresh margin / bound | 30 min / 60 s / 3 per run |
| Download attempts | 5, 30 s → 15 min |
| Verify attempts | 2 |
| `INSTALL_MODE` | `PACKAGE_MANAGER`, both build types |
| Staged override path | `<filesDir>/override/<packageName>.apk` |
| Override target partition | `/product/priv-app/` |
| Broadcast prefix | `com.lucid.appmanager.debug.` |

---

## 13. Not proven, and open

**The download has never met a real server.** No backend exists, so `FakeDownloadEngine` is the
`Dependencies` default and the debug engine stands in via `USE_APK`. Resume, `If-Range`, `Content-Range`
validation and the retry ladder are written but untested against HTTP.

**The cloud contract has no sign-off.** `Manifest`, `SignedUrl` and the two-call split are our proposal.
`ManifestChecker` and `UrlSession` are the blast radius if the real contract differs.

**Privileged install is unverified on a head unit.** Only `INSTALL_NEEDS_USER_ACTION` has been seen, which
is the correct unprivileged answer. The silent path needs the privapp allowlist in the image.

**The rename reaches outside this repo.** The package is `com.lucid.appmanager`, so the system image needs
`privapp-permissions-com.lucid.appmanager.xml` and a matching preload directory, or `INSTALL_PACKAGES` is
denied at boot and the installer stops working — silently, in the same shape as the `QUERY_ALL_PACKAGES`
bug above.

**`BOOT_COUNT`** is assumed readable from `Settings.Global.BOOT_COUNT`. If the image does not expose it,
the override check degrades to "report on the first start after staging".

**Deferred.** A platform helper service for privileged partition writes — the production answer to path B.
Uninstall, downgrade, rollback. Split APKs and APEX. More than one manifest in flight. Unit tests.

---

## 14. Driving states

Mapped by `DrivingStateMapper` from **ignition first**, then gear and speed. Ignition decides two states on
its own — neither `TURNED_OFF` nor `ACCESSORY` can be derived from gear and speed, since a car with the
ignition off reports gear Park and zero speed exactly like one with the ignition on.

| State | Ignition | Gear / speed | Gate |
|---|---|---|---|
| `TURNED_OFF` | off | — | allowed |
| `ACCESSORY` | accessory | — | allowed |
| `PARKED` | on | Park, stationary | allowed |
| `STOPPED` | on | in gear, stationary | allowed — see below |
| `DRIVING` | on | moving | **blocked** |
| `UNKNOWN` | unreadable | unreadable | **blocked** |

`UNKNOWN` is not a vehicle state. It means the signal could not be read, which is the case before the first
reading and whenever the signal library is unavailable, and it blocks so that a missing reading is never
mistaken for a stationary vehicle.

**`STOPPED` is permitted by decision (2026-11-05), and it is the one entry that is not inherently safe.**
A car stationary in gear — at a traffic light — can move again without warning. A download is covered: the
mid-run watcher pauses it the moment the state becomes `DRIVING`. An install is not, because once committed
it has no equivalent check, so an app can restart as the light turns green. Dropping `STOPPED` from
`PolicyEvaluator.PERMITTED_DRIVING` is a one-line change if that matters.

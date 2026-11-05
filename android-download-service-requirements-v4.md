# Android Download Service: Requirements (v4) — artifact URLs, real transfer, install

Extends [v3](android-download-service-requirements-v3.md). v3 stays valid: the scheduler, policy gate,
run coordinator, waiting watcher, events and AIDL are unchanged. This document adds the cloud contract,
the real download engine, two install paths behind a flag, and the debug action surface.

FR numbering continues from v3 (which ends at FR-27). Assumptions continue from A11.

---

## 0. Scope note (read first)

This phase cashes in most of v3 section 12 (deferred to the DownloadWorker phase) **and** adds install,
which v3 did not cover at all. Today the repo has `FakeDownloadEngine`, a stub `CatalogueProvider`, and
**no install code of any kind**. Everything below is new surface rather than modification, except where
marked.

Two things are deliberately *not* what they might look like:

- **The app never writes to a system partition.** The "override" install path stages a verified APK and
  emits an adb procedure. The write and the reboot are done by a human or a test script (A14).
- **The 30-minute URL TTL is shorter than this service's normal wait.** The scheduled run starts at 02:00
  and may sit in `WAITING` for hours. The URL is therefore fetched *last*, not first (FR-30).

---

## 1. Scope

### In scope

| Area | Summary |
|---|---|
| Cloud contract | Artifact fetch (unchanged in intent), then a separate signed-URL call with a TTL |
| Download engine | Replace `FakeDownloadEngine` with a real HTTP transfer: resume, retries, integrity |
| Install path A | `PackageInstaller` session as a privileged app — the default |
| Install path B | Stage a verified APK for a partition override applied out-of-band at the next reboot |
| Feature flag | `installMode`, runtime-togglable, alongside the existing kill switch |
| Debug actions | `DebugFlagActions`: every use case drivable from adb and from code, debug builds only |

### Out of scope

- Writing to `/system` or `/product` from the app (A14).
- Rebooting the vehicle from the app (A15).
- A platform helper service for privileged partition writes — noted as the production answer, owned elsewhere.
- Uninstall, downgrade, rollback of an installed app.
- Multi-APK / split APK / APEX.
- Tests. Per the project decision, KDoc and README only this phase.

---

## 2. Confirmed inputs and assumptions

| # | Item | Decision | Status |
|---|---|---|---|
| A12 | Artifact vs URL | Two calls. The artifact describes *what* to install; a second call mints the download URL. A 30-minute TTL cannot live inside a long-lived artifact | Confirmed |
| A13 | URL TTL | 30 minutes. Fetched lazily immediately before transfer, re-fetched transparently on mid-transfer expiry | Confirmed |
| A14 | Partition write | The app never performs it. `DebugFlagActions` documents and emits the adb sequence; the app downloads, verifies and stages | Confirmed |
| A15 | Reboot | The app never reboots the vehicle. A staged override applies at the next natural ignition cycle | Confirmed |
| A16 | Override viability | Requires a writable partition (userdebug, verity off). Permanently unavailable on a production-signed image | Confirmed constraint |
| A17 | Install flag | Global, runtime-togglable, DataStore-backed, default `PACKAGE_MANAGER` from `BuildConfig` | Confirmed |
| A18 | Debug surface | Debug builds only. A **non-exported** receiver plus a callable object. Absent from release | Confirmed |
| A19 | Install gating | Install obeys the same driving gate as download (project decision 2026-10-12). Not parked means no install | Assumed from prior decision |
| A20 | Backend | Does not exist yet. The contract below is *our* proposal; the backend team has not reviewed it | **Needs confirmation** |
| A21 | Signing | The APK is signed by the same key as the app it replaces, or install fails with a signature conflict. Not something the store can repair | Assumed |
| A22 | Package visibility | `QUERY_ALL_PACKAGES` is **required**. The catalogue is server-driven, so the packages to inspect are unknown at build time and a static `<queries>` list cannot cover them | Confirmed by testing |

> **A20 is the one to watch.** Both halves of §4.1 are written by us against a backend that has not agreed
> to them. If the real contract differs, the lazy-URL design (FR-30) is the part most likely to need rework.

---

## 3. Functional requirements

### Package visibility

- **FR-27a.** The app SHALL hold `QUERY_ALL_PACKAGES` (A22). Without it `getPackageInfo` throws
  `NameNotFoundException` for every other package, and three requirements below degrade **silently**
  rather than failing: FR-29 never short-circuits so every run re-downloads; FR-40 treats every app as a
  first install and skips the signature check entirely, which is a security hole; and FR-44 cannot confirm
  an install. Found by testing — the code was correct and the manifest was not.

### Cloud contract

- **FR-28.** `CatalogueProvider` returns an **artifact** per available app: package name, version name,
  `versionCode`, expected size in bytes, an integrity digest, and an opaque `artifactId`. It does **not**
  return a download URL.
- **FR-29.** Before any transfer, the service compares the artifact's `versionCode` against the installed
  package. Equal or lower means the run completes immediately with `UP_TO_DATE` and no bytes are moved.
  (v3 section 12, "update detection".)
- **FR-30.** The signed URL is fetched from `ArtifactUrlProvider` **only once the policy gate has opened
  and the transfer is about to begin** — never while `WAITING`, never at schedule time. The response
  carries the URL and an absolute expiry.
- **FR-31.** If the URL expires mid-transfer (or the server returns 401/403 on a resume), the service
  re-fetches a fresh URL and resumes from the current byte offset using `Range` + `If-Range`. This is
  transparent: no user-visible failure, no restart of the run.
- **FR-32.** URL re-fetches within one run are bounded (default 3). Exhausting them fails the run with
  `URL_REFRESH_EXHAUSTED` rather than looping.
- **FR-33.** A URL fetch that fails before any bytes are transferred is treated as a transport failure and
  follows the retry policy in FR-36.

### Download engine

- **FR-34.** `FakeDownloadEngine` is replaced by an HTTP engine on `Dispatchers.IO`, writing to a `.part`
  file with an atomic rename on success. The v3 engine contract (`download`/`discard`, cancellation means
  pause) is unchanged — the shell does not change shape.
- **FR-35.** Resume uses `Range` with `If-Range`. Only `206` with a matching `Content-Range` is accepted;
  anything else restarts from zero and emits `RESTARTED_FROM_ZERO`.
- **FR-36.** Retry policy: 5 attempts, exponential backoff from 30 s capped at 15 min. 5xx, timeouts and
  connection resets retry. 404 fails immediately. **401/403 trigger a URL re-fetch (FR-31) before counting
  as a failure**, because the expected cause is an expired URL rather than a real authorization failure.
- **FR-37.** `DownloadResult` gains failure variants. `RunCoordinator` maps them to retry, wait or failed,
  as anticipated in v3 section 12.
- **FR-38.** Storage: a run is blocked when free space is below expected size plus margin. `BlockReason`
  gains `STORAGE` and `PolicyEvaluator` gains a fourth rule.

### Integrity

- **FR-39.** After transfer, before install or staging: verify byte length against the artifact, verify the
  integrity digest, and verify the file parses as an APK.
- **FR-40.** Verify the APK's signing certificate matches the certificate of the currently installed
  package. A digest proves the bytes arrived intact; it does not prove the APK is legitimate.
- **FR-41.** Any verification failure deletes the file, emits `VERIFY_FAILED`, and retries from scratch
  **once**. A second failure fails the run. A file that failed verification is never installed or staged.

### Install — common

- **FR-42.** Install is gated by the same policy evaluation as download (A19). Not parked means the APK
  stays on disk and the run waits, exactly as a download wait does.
- **FR-43.** The install path is chosen by `Flags.installMode` read at the moment install begins, so a flag
  change mid-run takes effect on the next run rather than halfway through this one.
- **FR-44.** After any install attempt, the installed `versionCode` is re-read from `PackageManager`. That
  reading — not the install result — decides success. (Consistent with the store's existing rule that the
  device is the source of truth.)

### Install path A — PackageManager (default)

- **FR-45.** Install via a `PackageInstaller` session, `MODE_FULL_INSTALL`, committed with a `PendingIntent`.
- **FR-46.** As a privileged app holding `INSTALL_PACKAGES`, the session requests
  `USER_ACTION_NOT_REQUIRED` and completes with no dialog. On a build where the permission is not granted,
  the platform falls back to the confirmation dialog; the service reports `INSTALL_NEEDS_USER_ACTION`
  rather than silently hanging.
- **FR-47.** The staged APK is deleted once the session is committed, success or failure.

### Install path B — partition override

- **FR-48.** The service **stages** the verified APK at a fixed, documented path in app storage and records
  a pending override: package name, target `versionCode`, staged path, digest, staged-at timestamp.
- **FR-49.** The service **does not** remount, write to a partition, or reboot (A14, A15). It emits
  `OVERRIDE_STAGED` and logs the exact adb sequence needed to apply it.
- **FR-50.** On every start, the service checks any pending override against the installed `versionCode`.
  Match means the override landed: clear the record and emit `OVERRIDE_APPLIED`. No match after a reboot
  has been observed means it did not: emit `OVERRIDE_NOT_APPLIED` and keep the record for inspection.
- **FR-51.** The pending override survives process death and reboot. It is cleared only by FR-50, by an
  explicit debug action, or by the kill switch reset.

### Feature flag

- **FR-52.** `Flags` gains `installMode: InstallMode` with values `PACKAGE_MANAGER` and `APK_OVERRIDE`.
  Default `PACKAGE_MANAGER`, sourced from `BuildConfig.INSTALL_MODE`, persisted in the existing DataStore,
  toggleable from the status screen and from `DebugFlagActions`.
- **FR-53.** Selecting `APK_OVERRIDE` on a build where the partition is not writable is **not** blocked —
  the app cannot reliably detect that. Staging succeeds and FR-50 reports that it did not apply. The README
  states the constraint (A16).

### Debug actions

- **FR-54.** A Kotlin `object DebugFlagActions` in the **debug source set only**, exposing one callable
  function per use case in §8.
- **FR-55.** A `DebugActionReceiver`, declared in the debug manifest only, maps adb broadcasts to those
  functions. It is **exported and gated on `android.permission.DUMP`**, not non-exported.

  > **Corrected after testing.** The original wording said non-exported. A non-exported receiver cannot
  > be reached by adb at all: `am broadcast` runs as the shell uid and the platform silently drops a
  > cross-uid delivery to a non-exported component — the command still prints `result=0`, which only means
  > "enqueued". Verified on an emulator. `DUMP` is `signature|privileged`, held by the shell and the system
  > and not grantable to a normal app, so adb works while other apps still cannot drive this app's
  > installer — which is the property this requirement was actually protecting.
- **FR-56.** Every action is documented with: what it does, the adb command, the programmatic call, and the
  use case it exercises. §8 is that catalogue and is mirrored in the README.
- **FR-57.** Neither the object nor the receiver is present in release builds. Nothing in `src/main`
  references them.

---

## 4. Component contracts

### 4.1 Cloud

```kotlin
/** What to install. Carries no URL — see ArtifactUrlProvider. */
data class Artifact(
    val artifactId: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val digest: Digest,
)

data class Digest(val algorithm: String, val value: String)

interface CatalogueProvider {           // existing, widened
    suspend fun availableArtifacts(): List<Artifact>
}

/** Mints a short-lived download URL. Called immediately before transfer, never earlier. */
interface ArtifactUrlProvider {
    suspend fun downloadUrl(artifactId: String): SignedUrl
}

data class SignedUrl(val url: String, val expiresAt: Instant)
```

### 4.2 Install

```kotlin
enum class InstallMode { PACKAGE_MANAGER, APK_OVERRIDE }

sealed interface InstallOutcome {
    data object Installed : InstallOutcome
    data object Staged : InstallOutcome                     // APK_OVERRIDE: awaiting reboot
    data object NeedsUserAction : InstallOutcome            // FR-46
    data class Failed(val code: Int, val message: String) : InstallOutcome
}

interface Installer {
    val mode: InstallMode
    suspend fun install(artifact: Artifact, apk: File): InstallOutcome
}

data class PendingOverride(
    val packageName: String,
    val targetVersionCode: Long,
    val stagedPath: String,
    val digest: Digest,
    val stagedAt: Long,
)
```

### 4.3 Engine contract

Unchanged from v3 §4.3 in shape. `DownloadResult` gains failure variants (FR-37). The engine is
constructed per run with the `SignedUrl` and a refresh callback it may invoke on expiry (FR-31).

---

## 5. The two install paths compared

| | Path A — PackageManager | Path B — partition override |
|---|---|---|
| Who installs | The app, in-process | A human or script, via adb |
| Takes effect | Immediately | Next reboot |
| Needs | `INSTALL_PACKAGES` (privileged) | Writable partition: userdebug, verity off |
| Works on production image | Yes | **No** (A16) |
| Survives factory reset | No — it is a `/data` update | Yes, it is the partition content |
| App's responsibility | Download, verify, install | Download, verify, stage, report |
| Failure visible as | `InstallOutcome.Failed` | `OVERRIDE_NOT_APPLIED` after reboot |

---

## 6. State machine additions

`RunPhase` gains `INSTALLING`. The existing `IDLE / RUNNING / WAITING` transitions are unchanged.

```
RUNNING --download complete--> VERIFYING --ok--> INSTALLING --> IDLE
                                   |                  |
                              fail (FR-41)       not parked (FR-42)
                                   |                  |
                                   v                  v
                                 FAILED            WAITING(DRIVING)
```

`BlockReason` gains `STORAGE` (FR-38).

---

## 7. Event and log additions

Continuing the v3 catalogue (tag `DlService`):

| Event | When |
|---|---|
| `UP_TO_DATE` | Installed version already at or above the artifact (FR-29) |
| `URL_FETCHED` | A signed URL was minted, with its expiry |
| `URL_REFRESHED` | Transparent mid-transfer re-fetch (FR-31) |
| `URL_REFRESH_EXHAUSTED` | Bound hit (FR-32) |
| `RESTARTED_FROM_ZERO` | Resume rejected (FR-35) |
| `VERIFY_FAILED` | Length, digest, APK parse or signature mismatch (FR-39–41) |
| `BLOCKED_STORAGE` | Free space below size plus margin (FR-38) |
| `INSTALL_STARTED` / `INSTALL_COMPLETE` / `INSTALL_FAILED` | Path A |
| `INSTALL_NEEDS_USER_ACTION` | Path A without the privileged permission (FR-46) |
| `OVERRIDE_STAGED` | Path B, with the adb sequence logged (FR-49) |
| `OVERRIDE_APPLIED` / `OVERRIDE_NOT_APPLIED` | Checked at next start (FR-50) |

---

## 8. `DebugFlagActions` catalogue

Debug builds only (FR-57). Every action has an adb form and a programmatic form. Broadcast action prefix
`com.lucid.appmanager.debug.`, extras as shown.

```
adb shell am broadcast -n com.lucid.appmanager/.debug.DebugActionReceiver \
    -a com.lucid.appmanager.debug.<ACTION> [--es|--ei|--ez <key> <value>]
```

### 8.1 Flags

| Use case | adb | API |
|---|---|---|
| Kill switch on/off | `-a …SET_SERVICE_ENABLED --ez enabled true` | `setServiceEnabled(Boolean)` |
| Choose install path | `-a …SET_INSTALL_MODE --es mode APK_OVERRIDE` | `setInstallMode(InstallMode)` |
| Read current flags | `-a …DUMP_FLAGS` | `dumpFlags(): String` |

### 8.2 Schedule

| Use case | adb | API |
|---|---|---|
| Run now, skip the 02:00 wait | `-a …TRIGGER_NOW` | `triggerNow()` |
| Move the daily time | `-a …SET_SCHEDULE_TIME --es at 02:00` | `setScheduleTime(LocalTime)` |
| Prove the kill switch cancels work | `-a …CANCEL_ALL_WORK` | `cancelAllWork()` |

### 8.3 Policy gate — forcing each block reason

| Use case | adb | API |
|---|---|---|
| Driving blocks start and pauses mid-run | `-a …SET_DRIVING --es state MOVING` | `setDrivingState(DrivingState)` |
| Battery below threshold; resume margin | `-a …SET_BATTERY --ei level 15 --ez charging false` | `setBattery(Int, Boolean)` |
| Network class, incl. tether rejection | `-a …SET_NETWORK --es class CELLULAR` | `setNetworkClass(NetworkClass)` |
| Cellular without a subscription | `-a …SET_CELLULAR_SUBSCRIBED --ez subscribed false` | `setCellularSubscribed(Boolean)` |
| Storage block (FR-38) | `-a …SET_FREE_SPACE --ei mb 100` | `setFreeSpaceMb(Int)` |
| Clear all overrides, use real signals | `-a …CLEAR_SIGNAL_OVERRIDES` | `clearSignalOverrides()` |

### 8.4 Cloud and URL

| Use case | adb | API |
|---|---|---|
| Point at a local/stub catalogue | `-a …SET_CATALOGUE --es source STUB` | `setCatalogueSource(Source)` |
| Inject an artifact to install | `-a …SET_ARTIFACT --es pkg com.x --ei code 42 --es digest <hex>` | `setArtifact(Artifact)` |
| Force a URL expiry mid-transfer (FR-31) | `-a …EXPIRE_URL_NOW` | `expireUrlNow()` |
| Force the refresh bound (FR-32) | `-a …FAIL_URL_FETCH --ei times 4` | `failUrlFetch(Int)` |
| Prove up-to-date short-circuit (FR-29) | `-a …SET_ARTIFACT --ei code <installed>` | — |

### 8.5 Transfer

| Use case | adb | API |
|---|---|---|
| Server without Range support (FR-35) | `-a …SET_RANGE_SUPPORT --ez supported false` | `setRangeSupport(Boolean)` |
| Force an HTTP status | `-a …FORCE_HTTP_STATUS --ei code 503` | `forceHttpStatus(Int)` |
| Exercise the retry ladder (FR-36) | `-a …FORCE_TRANSIENT_FAILURES --ei times 3` | `forceTransientFailures(Int)` |
| Slow the transfer to watch pause/resume | `-a …SET_SPEED --ei kbps 500` | `setSpeedKbps(Int)` |

### 8.6 Integrity

| Use case | adb | API |
|---|---|---|
| Digest mismatch (FR-39) | `-a …CORRUPT_NEXT_DOWNLOAD` | `corruptNextDownload()` |
| Truncated file / length mismatch | `-a …TRUNCATE_NEXT_DOWNLOAD --ei bytes 1024` | `truncateNextDownload(Int)` |
| Signature mismatch (FR-40) | `-a …USE_APK --es path /data/local/tmp/wrong-signer.apk` | `useLocalApk(String)` |

### 8.7 Install — path A

| Use case | adb | API |
|---|---|---|
| Install a local APK now, skip download | `-a …INSTALL_NOW --es path /data/local/tmp/app.apk` | `installNow(File)` |
| Force an install failure | `-a …FORCE_INSTALL_FAILURE --ei code 4` | `forceInstallFailure(Int)` |
| Prove install is driving-gated (FR-42) | `SET_DRIVING MOVING` then `INSTALL_NOW` | — |

### 8.8 Install — path B

| Use case | adb | API |
|---|---|---|
| Stage an override | `-a …STAGE_OVERRIDE --es path /data/local/tmp/app.apk` | `stageOverride(File)` |
| Print the adb sequence to apply it (FR-49) | `-a …PRINT_OVERRIDE_COMMANDS` | `printOverrideCommands(): String` |
| Inspect the pending override | `-a …DUMP_PENDING_OVERRIDE` | `dumpPendingOverride(): String` |
| Re-run the post-reboot check (FR-50) | `-a …CHECK_OVERRIDE_APPLIED` | `checkOverrideApplied()` |
| Clear it | `-a …CLEAR_PENDING_OVERRIDE` | `clearPendingOverride()` |

The sequence `PRINT_OVERRIDE_COMMANDS` emits is, for reference:

```bash
adb root && adb remount
adb push <stagedPath> /product/priv-app/<App>/<App>.apk
adb shell chmod 644 /product/priv-app/<App>/<App>.apk
adb reboot
```

### 8.9 State

| Use case | adb | API |
|---|---|---|
| Dump run state, config, flags | `-a …DUMP_STATE` | `dumpState(): String` |
| Reset everything to defaults | `-a …RESET_ALL` | `resetAll()` |

---

## 9. Class breakdown (new and changed)

| Class | Package | Note |
|---|---|---|
| `Artifact`, `Digest`, `SignedUrl` | `catalogue` | New data model |
| `CatalogueProvider` | `catalogue` | **Changed**: returns artifacts, not `AppInfo` |
| `ArtifactUrlProvider` | `catalogue` | New |
| `HttpDownloadEngine` | `download.engine` | New, replaces `FakeDownloadEngine` in release wiring |
| `IntegrityVerifier` | `download.verify` | New |
| `Installer`, `InstallOutcome`, `InstallMode` | `install` | New package |
| `PackageManagerInstaller` | `install` | Path A |
| `OverrideStagingInstaller` | `install` | Path B |
| `PendingOverrideStore` | `install` | New, DataStore |
| `Flags`, `DataStoreFeatureFlags` | `download.config` | **Changed**: gains `installMode` |
| `PolicyEvaluator`, `BlockReason` | `download.policy` | **Changed**: gains `STORAGE` |
| `RunCoordinator`, `RunPhase` | `download.run` | **Changed**: gains verify + install stages |
| `DebugFlagActions`, `DebugActionReceiver` | `debug` | New, **debug source set only** |

---

## 10. Non-functional

- The debug surface adds nothing to the release APK (FR-57) — verifiable by checking the release build for
  the class.
- Verification runs off the main thread; a digest over a 600 MB file is not free.
- The staged APK and the `.part` file are in app-private storage, never shared storage.
- No new runtime permission beyond what v3 and the privileged install already require.

---

## 11. Acceptance cases

| # | Given | When | Then |
|---|---|---|---|
| AC-28 | Installed version equals the artifact | Run starts | `UP_TO_DATE`, no bytes transferred |
| AC-29 | Run waits 2 h on driving | Gate opens | URL is fetched *then*, not at schedule time |
| AC-30 | URL expires at 50% | Transfer continues | `URL_REFRESHED`, resumes from offset, no user-visible failure |
| AC-31 | Server returns 403 on resume | — | URL re-fetch first; only then the retry ladder |
| AC-32 | Server lacks Range support | Resume attempted | `RESTARTED_FROM_ZERO`, run still completes |
| AC-33 | Digest mismatch | Verify | `VERIFY_FAILED`, file deleted, one retry from scratch |
| AC-34 | APK signed by a different key | Verify | Refused before install; not installed or staged |
| AC-35 | Flag `PACKAGE_MANAGER`, privileged | Install | Silent install, `versionCode` confirms (FR-44) |
| AC-36 | Flag `PACKAGE_MANAGER`, not privileged | Install | `INSTALL_NEEDS_USER_ACTION`, no hang |
| AC-37 | Flag `APK_OVERRIDE` | Install | `OVERRIDE_STAGED`, adb sequence logged, nothing installed |
| AC-38 | Override staged, adb applied, rebooted | Next start | `OVERRIDE_APPLIED`, record cleared |
| AC-39 | Override staged, never applied, rebooted | Next start | `OVERRIDE_NOT_APPLIED`, record kept |
| AC-40 | Vehicle moving | Install due | Waits; APK stays on disk; resumes when parked |
| AC-41 | Release build | Inspect | No `DebugFlagActions`, no `DebugActionReceiver` |

---

## 12. Deferred

- Platform helper service for privileged partition writes — the production answer to path B.
- Uninstall, downgrade, rollback.
- Split APKs, APEX.
- Multiple artifacts per run (today: one app).
- Unit tests, per the standing project decision.

---

## 13. Confirmations (defaults apply unless you change them)

| # | Item | Default |
|---|---|---|
| C1 | Digest algorithm | SHA-256 |
| C2 | URL re-fetch bound per run | 3 (FR-32) |
| C3 | Storage margin above expected size | 10% |
| C4 | Staged override path | `<filesDir>/override/<packageName>.apk` |
| C5 | `BuildConfig.INSTALL_MODE` default | `PACKAGE_MANAGER`, both build types |
| C6 | Broadcast action prefix | `com.lucid.appmanager.debug.` |
| C7 | Target partition in the printed adb sequence | `/product/priv-app/` |
| C8 | Retry ladder | Unchanged from v3 §12: 5 attempts, 30 s → 15 min |

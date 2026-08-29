# LucidAppStore — Requirements Specification

| Field | Value |
|---|---|
| Product | LucidAppStore |
| Package | `com.lucid.auto.appstore` |
| Platform | Android only (Kotlin + Jetpack Compose) |
| Document version | 1.0 |
| Status | Approved for implementation |

---

## 1. Purpose

LucidAppStore is the on-vehicle application catalogue for Lucid head units. It is the single
place from which a Lucid app can be **installed**, **updated**, or seen as **already current**.
It replaces manual APK sideloading and gives the vehicle a controlled, auditable update path.

## 2. Scope

### 2.1 In scope

- A single Android application that lists a fixed, curated set of managed apps.
- Reading the **locally installed version** of each managed app from the device.
- Fetching a **remote catalogue** (JSON) describing the latest available version of each app.
- Deriving and displaying a per-app state: `Install`, `Update`, or `Installed`.
- Downloading and installing/updating APKs.
- An **auto-update boolean flag**; when enabled, eligible updates are applied without user taps.
- A local development server so the whole flow is testable over Wi-Fi.

### 2.2 Managed applications (fixed catalogue)

| # | Display name | Package name |
|---|---|---|
| 1 | Lucid Media | `com.lucid.auto.media` |
| 2 | Lucid Radio | `com.lucid.auto.radio` |
| 3 | Spotify      | `com.spotify.music` |

The catalogue is intentionally small. Package names are declared statically in
`AndroidManifest.xml` under `<queries>` so the app does **not** need `QUERY_ALL_PACKAGES`.

### 2.3 Out of scope (future)

1. A push-notification server that notifies the vehicle when new versions are published.
2. A production version/catalogue service (the current server is a local test harness only).
3. Uninstall, rollback, staged/canary rollouts, per-user entitlement, payments, search, reviews.

---

## 3. Definitions

| Term | Meaning |
|---|---|
| **Managed app** | An app in the fixed catalogue above. |
| **Local version** | `versionCode` / `versionName` read from the device via `PackageManager`. |
| **Available version** | `versionCode` / `versionName` published by the catalogue server. |
| **Silent install** | Install with no system confirmation dialog. Requires privileged permissions. |
| **Interactive install** | Install that shows the platform's "Do you want to install?" dialog. |

---

## 4. Functional requirements

### FR-1 — Read local app versions

**FR-1.1** On launch, and on every manual refresh, the app SHALL read the installed version of
each managed package via `PackageManager.getPackageInfo(packageName, 0)`.

**FR-1.2** The numeric version used for all comparisons SHALL be
`PackageInfoCompat.getLongVersionCode(packageInfo)` (`versionCode` on < API 28,
`longVersionCode` on >= API 28). `versionName` is **display only** and SHALL NOT be used for
ordering, because it is an arbitrary developer-chosen string.

**FR-1.3** A `PackageManager.NameNotFoundException` SHALL be interpreted as *not installed*, not
as an error.

**FR-1.4** Because Android 11 (API 30) filters package visibility, the manifest SHALL declare each
managed package inside a `<queries>` element. Without this, `getPackageInfo` throws
`NameNotFoundException` for installed apps and every app would falsely show `Install`.

**FR-1.5** The icon shown for an installed app SHALL come from
`PackageManager.getApplicationIcon(packageName)`. For a not-installed app it SHALL come from the
`iconUrl` in the catalogue. If neither is available a neutral placeholder SHALL be shown.

### FR-2 — Fetch the remote catalogue

**FR-2.1** On launch the app SHALL `GET {baseUrl}/api/v1/apps` and parse the JSON catalogue.

**FR-2.2** The response schema:

```jsonc
{
  "manifestVersion": 1,
  "generatedAt": "2026-08-28T10:00:00Z",
  "apps": [
    {
      "packageName": "com.lucid.auto.media",
      "title": "Lucid Media",
      "versionName": "1.2.0",
      "versionCode": 10200,
      "apkUrl": "http://192.168.1.20:8080/apks/lucid-media-1.2.0.apk",
      "iconUrl": "http://192.168.1.20:8080/icons/lucid-media.png",
      "sizeBytes": 4194304,
      "sha256": "…",              // optional; verified when present
      "releaseNotes": "…"          // optional
    }
  ]
}
```

**FR-2.3** Unknown JSON fields SHALL be ignored (forward compatibility).

**FR-2.4** If the fetch fails, the app SHALL still render the list using local data only, and
SHALL show a non-blocking error banner with a Retry action. Managed apps that are installed
appear as `Installed`; apps with no local and no remote data are omitted.

### FR-3 — State derivation

**FR-3.1** For each managed package the state SHALL be derived as:

| Local | Remote | `remote.versionCode > local.versionCode` | State |
|---|---|---|---|
| absent | present | — | `INSTALL` |
| present | present | true | `UPDATE` |
| present | present | false | `INSTALLED` |
| present | absent | — | `INSTALLED` |
| absent | absent | — | omitted from the list |

**FR-3.2** State derivation SHALL be a pure function of (local info, remote info) with no
Android dependencies, so it is unit-testable on the JVM.

**FR-3.3** The state type SHALL model exactly these three values. Transient conditions
(downloading, installing) and failures SHALL NOT be representable as states, so no code path can
render a fourth value in a row.

### FR-4 — List UI

**FR-4.1** The main screen SHALL present managed apps in a vertically scrolling list
(`LazyColumn`).

**FR-4.2** Each row SHALL show exactly four things and **nothing else**:
**app icon**, **app name/title**, **current version**, and an
**action control reflecting the state**.

**FR-4.2.1** A row SHALL NOT display error text or any other free-form prose. The only status
text permitted is the fixed three-word vocabulary in FR-4.5. Failure *reasons* — HTTP codes,
platform messages, integrity errors — SHALL be reported outside the list.

**FR-4.3** When idle, the version line SHALL contain version numbers only:
- `INSTALL` → `1.2.0` (the version that would be installed)
- `UPDATE` → `1.1.0 → 1.2.0`
- `INSTALLED` → `1.2.0`
- version unknown → `—`

**FR-4.4** Action control behaviour. The label SHALL always be one of exactly three values:
- `Install` → downloads and installs the APK.
- `Update` → downloads and installs over the existing app.
- `Installed` → **tapping does nothing** (control is rendered disabled/non-interactive).

**FR-4.5** While work is in flight the version line SHALL be replaced by short status text drawn
from a **fixed vocabulary of exactly three forms**:

| Condition | Text |
|---|---|
| Download running | `Downloading… n%` |
| Install running | `Installing…` |
| Last attempt failed | `Failed` |

The action control SHALL keep its state label (`Install`/`Update`/`Installed`) and SHALL NOT be
renamed. While downloading or installing it SHALL be non-interactive and show a spinner.

**FR-4.6** On failure the row SHALL show the bare word `Failed` and nothing more — never the
reason. The control SHALL remain its true state and stay tappable so the user can retry. The
reason SHALL be reported above the list, naming the app.

**FR-4.7** A `Failed` marker describes the last attempt, not the app. A refresh SHALL clear it;
in-flight work SHALL be left untouched.

**FR-4.8** The screen SHALL offer pull-to-refresh / a refresh action that re-runs FR-1 and FR-2.

### FR-5 — Install and update

**FR-5.1** APKs SHALL be downloaded to app-private cache storage, never to shared storage.

**FR-5.2** When the catalogue supplies a `sha256`, the downloaded file SHALL be verified before
install and rejected on mismatch.

**FR-5.3** Installation SHALL use `PackageInstaller` sessions
(`SessionParams(MODE_FULL_INSTALL)`), writing the APK into the session and committing with a
`PendingIntent` that receives the outcome.

**FR-5.4 — Dual-mode installer.** The installer SHALL detect its capability at runtime:

- **Silent mode** — used when `INSTALL_PACKAGES` is granted (privileged/system build). No user
  dialog for install or update.
- **Interactive mode** — used otherwise. The session commit returns
  `STATUS_PENDING_USER_ACTION`; the app SHALL launch the supplied confirmation intent.
  On API 31+ the session SHALL additionally request
  `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`, which lets the store silently **update**
  apps it originally installed, even unprivileged.

**FR-5.5** Downloaded APK files SHALL be deleted after the session is committed, on success or
failure.

**FR-5.6** After any install outcome the app SHALL re-read local versions (FR-1) so the row
settles on its true state.

### FR-6 — Auto-update flag

**FR-6.1** The app SHALL expose a single persisted boolean, `autoUpdateEnabled`, stored in
DataStore. Default: **disabled**.

**FR-6.2** The flag SHALL be toggleable from the main screen.

**FR-6.3** Auto-update SHALL run in **two places, and only while the flag is enabled**:
1. **On launch** — after the catalogue refresh, every app in `UPDATE` state is queued.
2. **Periodic worker** — a WorkManager `PeriodicWorkRequest` (6 h, `NetworkType.UNMETERED`,
   `BatteryNotLow`).

**FR-6.4** Enabling the flag SHALL enqueue the periodic worker. Disabling it SHALL cancel the
worker by unique name and SHALL suppress the on-launch pass. No background update work exists
while the flag is off.

**FR-6.5** A boot-completed receiver SHALL re-arm the worker only if the flag is enabled.

**FR-6.6** Auto-update SHALL only update apps already installed (`UPDATE` state). It SHALL NEVER
install a new app the user has not asked for.

**FR-6.7** Auto-update SHALL never apply to `INSTALL` or `INSTALLED` rows, and shall be a no-op
when the catalogue is unreachable.

### FR-7 — Configurable server endpoint

**FR-7.1** The catalogue base URL SHALL be a build-config value with a debug default of the
local test server, and SHALL be overridable at runtime from a settings sheet so the app can be
pointed at a laptop's LAN IP without a rebuild.

**FR-7.2** Debug builds SHALL permit cleartext HTTP to private network ranges via a network
security config; release builds SHALL NOT.

---

## 5. Permissions

### 5.1 Always required

| Permission | Reason |
|---|---|
| `android.permission.INTERNET` | Fetch catalogue JSON and APKs. |
| `android.permission.ACCESS_NETWORK_STATE` | Detect connectivity before refresh/auto-update. |
| `<queries>` package list | Read installed versions of the 3 managed apps on API 30+ (FR-1.4). |
| `android.permission.FOREGROUND_SERVICE` + `…_DATA_SYNC` | Keep long downloads alive. |
| `android.permission.POST_NOTIFICATIONS` | Progress/result notifications on API 33+. Runtime-requested. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Re-arm the auto-update worker (FR-6.5). |

### 5.2 Unprivileged (normal APK) install path

| Permission | Reason |
|---|---|
| `android.permission.REQUEST_INSTALL_PACKAGES` | Show the system install-confirmation dialog. Also gated by the per-app "Install unknown apps" user toggle. |
| `android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION` | API 31+. Silently **update** apps this store originally installed. Normal-protection, so a plain APK may hold it. |

### 5.3 Privileged (system) build

Granted only when the APK is placed in `/system/priv-app/LucidAppStore/` **and** allowlisted:

| Permission | Protection | Reason |
|---|---|---|
| `android.permission.INSTALL_PACKAGES` | `signature\|privileged` | Fully silent install of new apps. |
| `android.permission.DELETE_PACKAGES` | `signature\|privileged` | Silent removal (reserved for future use). |

The allowlist file `system/privapp-permissions-com.lucid.auto.appstore.xml` must be installed to
`/system/etc/permissions/` or `/product/etc/permissions/`. Without it, Android **refuses to boot
the app** (privileged permission not in allowlist).

`QUERY_ALL_PACKAGES` is deliberately **not** requested — the static `<queries>` list is
sufficient for a fixed catalogue and avoids a policy-sensitive permission.

---

## 6. Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-1 | Cold start to a rendered list ≤ 1 s using cached local data; the network refresh streams in. |
| NFR-2 | The UI thread SHALL never perform PackageManager, disk, or network work. All such work runs on `Dispatchers.IO`. |
| NFR-3 | Catalogue and install failures SHALL be surfaced as recoverable UI state, never as a crash. |
| NFR-4 | Downloads SHALL be resumable-safe: a partial file is discarded and re-downloaded, never installed. |
| NFR-5 | No secrets, tokens, or user PII are stored or logged. |
| NFR-6 | minSdk 29, targetSdk 35, compileSdk 35. |
| NFR-7 | Core logic (state resolution, version comparison, catalogue parsing, ViewModel, list UI) SHALL be covered by tests that run with `./gradlew test` — no emulator required. |

---

## 7. Acceptance criteria

| # | Given | When | Then |
|---|---|---|---|
| AC-1 | Spotify is not installed, catalogue lists it | app opens | row shows `Install` |
| AC-2 | Lucid Media 1.1.0 installed, catalogue has 1.2.0 | app opens | row shows `Update` and `1.1.0 → 1.2.0` |
| AC-3 | Lucid Radio 2.0.0 installed, catalogue has 2.0.0 | app opens | row shows `Installed` |
| AC-4 | A row shows `Installed` | user taps it | nothing happens; no download, no state change |
| AC-5 | A row shows `Install` | user taps it | APK downloads, install runs, row settles on `Installed` |
| AC-6 | A row shows `Update` | user taps it | APK downloads, update runs, row shows the new version |
| AC-7 | Auto-update **off**, one app in `UPDATE` | app opens | no download starts; row stays `Update` |
| AC-8 | Auto-update **on**, one app in `UPDATE` | app opens | that app updates without any tap |
| AC-9 | Auto-update **on**, one app in `INSTALL` | app opens | it is **not** installed automatically |
| AC-10 | Auto-update toggled **on** then **off** | — | the unique periodic work is enqueued, then cancelled |
| AC-11 | Server unreachable | app opens | list renders from local data with an error banner + Retry |
| AC-12 | Catalogue `sha256` does not match the download | install attempted | install is refused; row shows `Failed`, reason shown above the list |
| AC-13 | A download returns HTTP 404 | user taps `Update` | row shows `Failed` and keeps its `Update` control; the HTTP detail appears above the list, not in the row |
| AC-14 | Any row, any state, any progress | rendered | the version line is either version numbers or one of `Downloading… n%` / `Installing…` / `Failed` — never a reason |
| AC-15 | A row shows `Failed` | user taps the control | the attempt is retried |
| AC-16 | A row shows `Failed` | user refreshes | the marker clears and the row shows its version again |

---

## 8. Traceability — requirement → component

| Requirement | Primary component |
|---|---|
| FR-1 | `PackageManagerInspector` |
| FR-2 | `CatalogRepository`, `CatalogApi` |
| FR-3 | `AppStateResolver` |
| FR-4 | `AppListScreen`, `AppRow`, `AppListViewModel` |
| FR-5 | `ApkDownloader`, `SessionAppInstaller`, `InstallResultReceiver` |
| FR-6 | `SettingsRepository`, `AutoUpdateScheduler`, `AutoUpdateWorker`, `BootReceiver` |
| FR-7 | `AppStoreConfig`, `network_security_config.xml` |

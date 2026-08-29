# LucidAppStore — Architecture & Component Breakdown

> Answers the question *"What components are required to build the framework?"*

---

## 1. Layer diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│ UI (Compose)                                                         │
│   MainActivity → AppListScreen → AppRow / AutoUpdateToggle           │
│                        ▲ StateFlow<AppListUiState>                   │
│   AppListViewModel ────┘  (intents: refresh, act(pkg), setAutoUpdate)│
└───────────────────────────────┬──────────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────────┐
│ Domain                                                               │
│   AppRepository   — merges local + remote into List<AppItem>         │
│   AppStateResolver — PURE: (local, remote) → AppState                │
│   AppItem / AppState / ManagedApps                                   │
│   UpdateCoordinator — download → verify → install → re-read          │
└───┬────────────┬─────────────┬──────────────┬────────────────────────┘
    │            │             │              │
┌───▼─────┐ ┌────▼──────┐ ┌────▼────────┐ ┌───▼──────────┐
│ Local   │ │ Catalog   │ │ Download    │ │ Install      │
│ Package │ │ CatalogApi│ │ ApkDownloader│ │ AppInstaller │
│Inspector│ │ Repository│ │ (OkHttp +   │ │  ├ Silent    │
│(Package │ │ (OkHttp + │ │  SHA-256)   │ │  └ Interactive│
│ Manager)│ │  kotlinx) │ │             │ │ InstallResult │
└─────────┘ └───────────┘ └─────────────┘ │   Receiver    │
                                          └───────────────┘
┌──────────────────────────────────────────────────────────────────────┐
│ Settings & Scheduling                                                │
│   SettingsRepository (DataStore) — autoUpdateEnabled, baseUrl        │
│   AutoUpdateScheduler — enqueue/cancel unique periodic work          │
│   AutoUpdateWorker (CoroutineWorker) · BootReceiver                  │
└──────────────────────────────────────────────────────────────────────┘
```

Dependencies point **inward**. `AppStateResolver` and `AppItem` have zero Android imports, which
is what makes the core logic testable on a plain JVM.

---

## 2. Component catalogue

### 2.1 Local version discovery — **the core of the "how do we know the version?" question**

| Component | File | Responsibility |
|---|---|---|
| `InstalledApp` | `data/local/InstalledApp.kt` | Immutable snapshot: package, label, `versionName`, `versionCode: Long`. |
| `PackageInspector` | `data/local/PackageInspector.kt` | Interface — `getInstalled(pkg): InstalledApp?`, `getIcon(pkg): ImageBitmap?`. Fakeable in tests. |
| `PackageManagerInspector` | `data/local/PackageManagerInspector.kt` | Real impl over `PackageManager`. |

```kotlin
override fun getInstalled(packageName: String): InstalledApp? = try {
    val info = packageManager.getPackageInfo(packageName, 0)
    InstalledApp(
        packageName  = packageName,
        label        = info.applicationInfo?.loadLabel(packageManager)?.toString(),
        versionName  = info.versionName.orEmpty(),
        versionCode  = PackageInfoCompat.getLongVersionCode(info),   // ← the ordering key
    )
} catch (e: PackageManager.NameNotFoundException) {
    null                                                             // ← not installed
}
```

**Two things make or break this:**

1. `versionCode` (a monotonic `Long`), not `versionName` (a marketing string), is compared.
   `"1.10.0"` sorts *before* `"1.9.0"` as a string — using it for ordering is a classic bug.
2. On **API 30+** the platform filters which packages you can even see. `getPackageInfo` for an
   undeclared package throws `NameNotFoundException` *as if the app were not installed*. The fix
   is the `<queries>` block naming each managed package. Skipping it makes every row show
   `Install` on a device where all three apps are present.

### 2.2 Catalogue (remote versions)

| Component | Responsibility |
|---|---|
| `RemoteApp` | `@Serializable` DTO: packageName, title, versionName, versionCode, apkUrl, iconUrl, sizeBytes, sha256?, releaseNotes?. |
| `AppCatalog` | Wrapper: manifestVersion, generatedAt, apps. |
| `CatalogApi` | OkHttp `GET {baseUrl}/api/v1/apps` → `AppCatalog`. |
| `CatalogRepository` | Caches the last good catalogue in memory; exposes `Result<AppCatalog>`. |

`Json { ignoreUnknownKeys = true }` so the server can add fields without breaking shipped cars.

### 2.3 Merge & state

| Component | Responsibility |
|---|---|
| `ManagedApps` | The fixed list of 3 package names + display fallbacks. Single source of truth, also read by the manifest `<queries>` list. |
| `AppStateResolver` | **Pure function** `(InstalledApp?, RemoteApp?) → AppState`. The most-tested class in the project. |
| `AppItem` | What the UI renders: icon, title, version strings, state, progress. |
| `AppRepository` | For each managed package: inspect locally + look up remotely + resolve → `List<AppItem>`. |

### 2.4 Download

| Component | Responsibility |
|---|---|
| `ApkDownloader` | Streams the APK to `cacheDir/apks/`, emits `DownloadProgress`, verifies SHA-256, deletes partials. |
| `DownloadResult` | `Success(File)` / `Failure(reason)`. |

### 2.5 Install — the dual-mode installer

| Component | Responsibility |
|---|---|
| `AppInstaller` | Interface: `suspend fun install(pkg, apk): InstallOutcome`, plus `installMode`. |
| `SessionAppInstaller` | `PackageInstaller` session: create → `openWrite` → `fsync` → `commit(PendingIntent)`. |
| `InstallMode` | `SILENT` (privileged, `INSTALL_PACKAGES` granted) vs `INTERACTIVE`. Detected at runtime. |
| `InstallResultReceiver` | `BroadcastReceiver` for the commit `PendingIntent`. On `STATUS_PENDING_USER_ACTION` it launches the confirm intent; otherwise it completes the pending `CompletableDeferred`. |
| `InstallOutcome` | `Success` / `Failure(code, message)` / `Cancelled`. |

Runtime detection:

```kotlin
val installMode = if (context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES)
        == PackageManager.PERMISSION_GRANTED) InstallMode.SILENT else InstallMode.INTERACTIVE
```

On API 31+ the session also sets `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`. Combined with
`UPDATE_PACKAGES_WITHOUT_USER_ACTION`, an *unprivileged* store can silently update apps it
installed itself — enough to demo auto-update on a stock emulator.

### 2.6 Orchestration

| Component | Responsibility |
|---|---|
| `UpdateCoordinator` | The one place that runs download → verify → install → refresh, emitting per-package progress. Shared by the UI tap path and the auto-update path so they cannot diverge. |
| `InstallProgressStore` | In-memory `MutableStateFlow<Map<String, InstallProgress>>` merged into `AppItem`s. |

### 2.7 Settings & auto-update

| Component | Responsibility |
|---|---|
| `SettingsRepository` | DataStore Preferences: `autoUpdateEnabled: Flow<Boolean>` (default false) and `baseUrl`. |
| `AutoUpdateScheduler` | `enqueue()` → unique `PeriodicWorkRequest` (6 h, unmetered, battery-not-low, `ExistingPeriodicWorkPolicy.KEEP`). `cancel()` → `cancelUniqueWork`. Called on every flag change. |
| `AutoUpdateWorker` | `CoroutineWorker`: re-check the flag, refresh, update only `UPDATE`-state apps. |
| `BootReceiver` | `RECEIVE_BOOT_COMPLETED` → re-arm **only if** the flag is on. |

Because both the launch pass and the worker are gated on the same flag, "only when the flag is
enabled" holds in both directions: turning it off cancels the worker *and* short-circuits launch.

### 2.8 UI

| Component | Responsibility |
|---|---|
| `MainActivity` | Single activity, `setContent { LucidAppStoreTheme { AppListScreen() } }`. |
| `AppListViewModel` | Owns `StateFlow<AppListUiState>`; intents `refresh()`, `onAppAction(pkg)`, `setAutoUpdate(Boolean)`. |
| `AppListUiState` | `items`, `isRefreshing`, `errorMessage`, `autoUpdateEnabled`, `installMode`. |
| `AppListScreen` | Top bar (title, auto-update `Switch`, refresh), error banner, `LazyColumn`. |
| `AppRow` | Icon · title · version line · state control. `INSTALLED` renders as a disabled control — `onAppAction` is never invoked for it. |

Test tags (`appRow_<pkg>`, `actionButton_<pkg>`, `autoUpdateSwitch`) are set so Compose tests can
assert row content and tap behaviour without a device.

### 2.9 Wiring

| Component | Responsibility |
|---|---|
| `ServiceLocator` | Plain hand-rolled DI. Every dependency is an interface with a fake, so no DI framework is needed and tests construct objects directly. |
| `LucidAppStoreApplication` | Initialises the locator; re-arms the scheduler from the persisted flag. |
| `AppStoreConfig` | `BuildConfig.DEFAULT_CATALOG_BASE_URL`, overridable at runtime. |

---

## 3. Backend components (local test harness)

| Component | File | Responsibility |
|---|---|---|
| Catalogue endpoint | `server/server.js` | `GET /api/v1/apps` — reads `data/apps.json`, rewrites relative URLs to the requesting host so a phone on Wi-Fi gets a reachable absolute URL. |
| APK hosting | `server/apks/` | `GET /apks/<file>.apk`, served with `Content-Length` and range support. |
| Icon hosting | `server/icons/` | `GET /icons/<file>.png`. |
| Health | `/health` | Trivial reachability check for debugging Wi-Fi. |
| Admin helpers | `/admin/bump/:pkg` | Increments a `versionCode` in memory so `Installed` flips to `Update` without editing files. |

Startup prints every LAN IP so you can point the app at `http://<ip>:8080`.

---

## 4. Threading model

| Work | Dispatcher |
|---|---|
| `PackageManager` reads, icon decode | `Dispatchers.IO` |
| Catalogue fetch, APK download, SHA-256 | `Dispatchers.IO` (OkHttp) |
| State merge / resolution | `Dispatchers.Default` |
| Compose recomposition | Main |

The ViewModel exposes a single `StateFlow` via `stateIn(viewModelScope, WhileSubscribed(5_000), …)`.

---

## 5. What ships where

| Build | Install capability | Notes |
|---|---|---|
| `debug` APK, sideloaded | Interactive install; silent update of self-installed apps on API 31+ | Testable on a stock emulator today. |
| `release` APK, sideloaded | same | Cleartext HTTP disabled. |
| Privileged system image | Fully silent install + update | APK in `/system/priv-app/LucidAppStore/`, allowlist XML in `/system/etc/permissions/`, signed with the platform key. |

The code path is identical; only `InstallMode` differs, decided at runtime.

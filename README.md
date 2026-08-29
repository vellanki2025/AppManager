# LucidAppStore

An on-vehicle app store for Lucid head units. It lists a fixed set of managed apps, shows each
one's current state — **Install**, **Update**, or **Installed** — and installs or updates them
from a catalogue server. An auto-update flag applies pending updates with no user interaction.

Android only. Kotlin + Jetpack Compose.

| | |
|---|---|
| Package | `com.lucid.auto.appstore` |
| minSdk / targetSdk / compileSdk | 29 / 35 / 35 |
| UI | Jetpack Compose (Material 3) |
| Tests | 93 unit + Compose tests, no emulator needed |
| Docs | [Delivery plan](docs/DELIVERY_PLAN.md) · [Requirements](docs/REQUIREMENTS.md) · [Architecture](docs/ARCHITECTURE.md) · [Troubleshooting](#troubleshooting) |

---

## The two questions this project set out to answer

### 1. How does LucidAppStore get the local app version?

From `PackageManager`, not from a server:

```kotlin
val info = packageManager.getPackageInfo("com.lucid.auto.media", 0)
info.versionName                              // "1.1.0"  -> display only
PackageInfoCompat.getLongVersionCode(info)    // 10100    -> the comparison key
```

Three details make this correct rather than merely working:

1. **`versionCode`, never `versionName`.** `versionName` is a free-form marketing string.
   Compared as text, `"1.10.0"` sorts *below* `"1.9.0"` and updates silently stop being offered.
   `PackageInfoCompat.getLongVersionCode` reads `longVersionCode` on API 28+ and falls back to
   the deprecated `versionCode` below that.
2. **`NameNotFoundException` means "not installed"**, not "error" — it is the normal signal for
   the `Install` state.
3. **Package visibility (API 30+).** Since Android 11 the platform filters which packages an app
   can see. `getPackageInfo` throws `NameNotFoundException` for an undeclared package *even when
   that app is installed*. The symptom is every row showing `Install` on a device where all three
   apps are present. The fix is `QUERY_ALL_PACKAGES`. A static `<queries>` list cannot work here:
   the catalogue is server-driven, so the packages to inspect are unknown at build time, and any
   app added to the catalogue after the APK shipped would be invisible.

The state each row shows then falls out of one pure comparison
([`AppStateResolver`](automotive/src/main/kotlin/com/lucid/auto/appstore/domain/AppStateResolver.kt)):

| Local | Remote | Condition | State |
|---|---|---|---|
| absent | present | — | **Install** |
| present | present | `remote.versionCode > local.versionCode` | **Update** |
| present | present | otherwise | **Installed** |
| present | absent | — | **Installed** |
| absent | absent | — | omitted |

### 2. What permissions does it need?

**Always:**

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | fetch the catalogue and APKs |
| `QUERY_ALL_PACKAGES` | read local versions of arbitrary catalogue packages on API 30+ |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | keep long downloads alive |
| `POST_NOTIFICATIONS` | progress/result notifications (API 33+, runtime-requested) |
| `RECEIVE_BOOT_COMPLETED` | re-arm the auto-update worker after a reboot |

**Unprivileged install path (ordinary APK):**

| Permission | Why |
|---|---|
| `REQUEST_INSTALL_PACKAGES` | show the system install-confirmation dialog |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | API 31+. Silently **update** apps this store installed |

**Privileged system build:**

| Permission | Protection | Why |
|---|---|---|
| `INSTALL_PACKAGES` | `signature\|privileged` | fully silent install of new apps |
| `DELETE_PACKAGES` | `signature\|privileged` | silent uninstall (reserved) |

`QUERY_ALL_PACKAGES` is **required**, not optional, because the catalogue is server-driven. For a
privileged system app on a head unit it is granted at build time and carries none of the Play
Store review burden it would for a consumer app.

#### Does it have to be a system app?

Only for fully silent installs. The installer detects its capability at runtime, so one APK
serves both:

| Build | Mode | Behaviour |
|---|---|---|
| Sideloaded APK | `INTERACTIVE` | New installs show the platform dialog. Updates to apps *this store* installed still complete silently on API 31+. |
| `/system/priv-app` + allowlist | `SILENT` | Every install and update completes with no dialog. |

For the privileged build, put the APK at `/system/priv-app/LucidAppStore/LucidAppStore.apk`,
sign it with the platform key, and install
[`system/privapp-permissions-com.lucid.auto.appstore.xml`](system/privapp-permissions-com.lucid.auto.appstore.xml)
to `/system/etc/permissions/`. Android verifies the allowlist at boot; ship the two together or
the device refuses to boot the app.

---

## What a row shows

A row renders exactly four things and nothing else:

| | |
|---|---|
| **Icon** | `PackageManager` for installed apps, the catalogue's `iconUrl` otherwise |
| **Title** | the catalogue title, falling back to the on-device label |
| **Version** | `1.2.0`, or `1.1.0 → 1.2.0` for an update, or `—` |
| **State** | exactly one of `Install`, `Update`, `Installed` |

While work is running, the version line carries short status text from a **fixed vocabulary of
three forms** — and nothing else:

| Condition | Row shows |
|---|---|
| downloading | `Downloading… 40%` |
| installing | `Installing…` |
| last attempt failed | `Failed` |

**The row says _that_ something failed; never _why_.** `Download failed: HTTP 404` goes in the
banner above the list, named per app. This is enforced structurally rather than by convention:

- [`AppState`](automotive/src/main/kotlin/com/lucid/auto/appstore/domain/AppState.kt) has **three**
  values, so the action control can only ever read `Install`, `Update` or `Installed` — a busy or
  failed row is never renamed.
- [`InstallProgress.Failed`](automotive/src/main/kotlin/com/lucid/auto/appstore/domain/InstallProgress.kt)
  is a `data object` carrying **no message**. The reason travels back through
  `UpdateCoordinator`'s return value instead, so it cannot reach a row even by accident.
- [`AppItem`](automotive/src/main/kotlin/com/lucid/auto/appstore/domain/AppItem.kt) has no error or
  free-text field, so nothing can put prose into a row.
- A `Failed` marker describes the last attempt, not the app: it clears on refresh, and the row
  stays tappable to retry.

`AppItemTest` enumerates every state × progress × missing-version combination and asserts the
version line is either pure version text or an exact match for one of the three status forms.

---

## How the list is populated

**The catalogue drives the list.** The store fetches the catalogue, then asks the device for the
installed version of each package the catalogue names:

```
device: 228 installed apps        catalogue: 10 apps
                    ↓                        ↓
                    └────── 10 rows ─────────┘
```

- Apps in the catalogue → **listed**, with their state resolved against the device.
- Apps installed on the device but **not** in the catalogue → **never listed**. They are not the
  store's concern.
- The store's own package → **never listed**, even when the catalogue includes it (that entry is
  its [self-update](#self-update) channel).

Nothing hard-codes the app set. The current catalogue ships with Lucid Media, Lucid Radio and
Spotify, but apps can be added or removed server-side with no new store APK — which is why
`QUERY_ALL_PACKAGES` is required rather than a static `<queries>` list.

Offline behaviour follows from this: with a cached catalogue the last-known list stays on screen;
on a cold start with no catalogue ever fetched, the list is empty with an error banner. The cache
is in-memory, so it does not survive a process restart — see *Known gaps*.

### Reading local versions

Version reads are **targeted, not enumerated**: the store calls `getPackageInfo(pkg)` once per
catalogue entry. It never calls `getInstalledPackages()`, so a 10-app catalogue costs 10 lookups
whether the device has 20 apps or 200.

Those reads are deliberately **not cached**. `PackageManager` is already an in-memory cache in
`system_server`, so a lookup is sub-millisecond — and a local cache would go stale on every
install or uninstall the store did not perform itself (a user uninstalling from Settings, adb, an
MDM push), none of which it is notified about. A stale row saying `Installed` for a deleted app
is a much worse bug than the microseconds it would save.

**Icons are cached**, because they are the genuinely expensive part — decoding a drawable out of
an APK — and their invalidation key cannot go stale: `(package, versionCode)`. An icon cannot
change without the APK changing, and the APK cannot change without the versionCode changing.

The row list is also built off the main thread (`flowOn`) and conflated, because progress ticks
once per percent of a download — about 100 rebuilds per install.

### Self-update

The store lists itself in its own catalogue and updates itself through the same
download → verify → install path as any other app, so shipping a new store does not need an OTA.

Committing an update to your own package **kills your own process**, which shapes the rules:

| Rule | Why |
|---|---|
| Background worker only, never a tap | Killing the app under the user's finger is hostile |
| Always last in a sweep | A self-kill must not abandon other apps' updates |
| `sha256` mandatory | Every other app can be repaired by the store; a corrupt store cannot repair itself |
| Downgrades refused explicitly | Clearer than the platform's conflict error |
| Never depends on observing its own success | On the happy path the process dies before the result arrives |

Failure is safe: `PackageInstaller` is atomic, so either the new store lands or the old one keeps
running. In the privileged build the updated APK installs to `/data` as an *updated system app*
and keeps its `priv-app` grants **provided it is signed with the same platform key** — worth
verifying on the real head-unit image, since a signing mismatch silently costs it
`INSTALL_PACKAGES`.

---

## Components

```
UI            MainActivity -> AppListScreen -> AppRow      AppListViewModel
Domain        AppRepository · AppStateResolver · AppItem · UpdateCoordinator
Data          PackageManagerInspector   (local versions, PackageManager)
              CatalogRepository/Api     (remote versions, OkHttp + kotlinx.serialization)
              HttpApkDownloader         (streamed download + SHA-256)
              SessionAppInstaller       (PackageInstaller, silent | interactive)
              InstallResultReceiver     (session commit callback)
Settings      SettingsRepository        (DataStore: autoUpdateEnabled, baseUrl)
Scheduling    AutoUpdateScheduler · AutoUpdateWorker · BootReceiver
```

`AppStateResolver` and `AppItem` have no Android imports, which is what lets the core rules be
tested on a plain JVM. Full breakdown in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### Auto-update

A single persisted boolean, default **off**. While enabled it runs in exactly two places:

1. **On launch** — after the catalogue refresh, every app in `Update` state is applied.
2. **Periodic worker** — WorkManager, 6 h, unmetered network, battery not low.

Toggling the flag on enqueues the unique periodic work *and* refetches the catalogue before
acting (a stale cache would silently skip updates published since launch). Toggling it off
cancels the work by name and short-circuits the launch pass — no background update job exists
while it is off. A boot receiver re-arms the worker only when the flag is on.

Auto-update only touches apps in `Update` state. It never installs an app the owner has not
asked for.

---

## Build and run

Prerequisites: JDK 17+, Android SDK with API 35, Node 18+.

```bash
./gradlew :automotive:assembleDebug
```

```bash
./gradlew :automotive:testDebugUnitTest
```

**Always build release as well before shipping.** `lintVital` runs only on release, so a fatal
lint error can sit in a green debug build indefinitely:

```bash
./gradlew :automotive:assembleRelease
```

### APK size

| Build | Size |
|---|---|
| debug | ~27 MB |
| release (unsigned, no R8) | ~20 MB |

Two things keep it there:

- **`material-icons-extended` is deliberately not a dependency.** It packs thousands of icon
  classes and cost ~30 MB of dex for the one icon this app uses. `Icons.Filled.Refresh` lives in
  `material-icons-core`, which Material 3 already brings in transitively. If you ever add
  `implementation("androidx.compose.material:material-icons-extended")`, expect the APK to roughly
  double.
- **`isMinifyEnabled` is `false` for release**, deliberately and for now. R8 would cut the size
  substantially, but the catalogue is parsed by `kotlinx.serialization`, so enabling it needs the
  keep rules verified on a device rather than assumed. Treat that as a separate, tested change.

### Test over Wi-Fi

**1. Build the sample fixture apps and publish them.** `samples/` contains three tiny, real,
installable APKs — one per managed package — so the whole flow can be exercised without
production builds.

```bash
./tools/publish-samples.sh
```

**2. Start the catalogue server.** It prints every address it can be reached on.

```bash
cd server && npm install && npm start
```

```
  local        http://localhost:8080
  emulator     http://10.0.2.2:8080       <- use this in an AVD
  wifi         http://192.168.1.20:8080   <- use this on a real device
```

**3. Point the app at it.** Debug builds default to `http://10.0.2.2:8080` (the emulator's route
to your laptop). For a real device on the same Wi-Fi, either edit
`DEFAULT_CATALOG_BASE_URL` in [automotive/build.gradle.kts](automotive/build.gradle.kts) to your LAN IP, or
override it at runtime via `AppListViewModel.setCatalogBaseUrl`. Cleartext HTTP is permitted only
for local addresses, via [network_security_config.xml](automotive/src/main/res/xml/network_security_config.xml);
add your LAN IP there when testing on a device.

**4. Seed the device, then publish something newer.**

```bash
adb install -r -t samples/app/build/outputs/apk/media/debug/app-media-debug.apk
```

```bash
./tools/publish-samples.sh --bump 5
```

Reopen LucidAppStore: Lucid Media now shows `1.1.0 → 1.1.5` with an **Update** button, Spotify
shows **Install**, and anything already current shows a greyed-out **Installed**.

If you get a connection error instead, see [Troubleshooting](#troubleshooting) — the usual cause
is the server not running, or running on a port the app is not calling.

An unprivileged build also needs the per-app "install unknown apps" toggle:

```bash
adb shell appops set com.lucid.auto.appstore REQUEST_INSTALL_PACKAGES allow
```

### Server endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/apps` | the catalogue; URLs rewritten to the requesting host |
| `GET` | `/apks/<file>.apk` | APK download |
| `GET` | `/icons/<file>.png` | icon for a not-yet-installed app |
| `GET` | `/health` | reachability check |
| `POST` | `/admin/bump/:pkg` | raise a `versionCode` to flip a row to `Update` |
| `POST` | `/admin/set/:pkg` | set `versionCode` / `versionName` explicitly |
| `POST` | `/admin/reload` | re-read `data/apps.json` |

`/admin/bump` changes only what the catalogue *claims*. It is useful for exercising the UI
states, but installing will not satisfy it and the row stays on `Update`. Use
`./tools/publish-samples.sh --bump N` to publish a genuinely newer APK.

---

---

## Troubleshooting

### `failed to connect to 10.0.2.2:8080`

`10.0.2.2` is the emulator's alias for your Mac's loopback and is almost never the problem.
The error means nothing is listening on that port. Since v1.1 the banner names the exact URL it
tried, so read it before changing anything.

Check what is actually listening:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

| What you see | Cause | Fix |
|---|---|---|
| no output | server not running | start it: `cd server && npm start` |
| `node ... TCP *:8081` | server on the wrong port | restart without `PORT=`, or repoint the app (below) |
| `node ... TCP 127.0.0.1:8080` | bound to loopback only | must be `*:8080` / `0.0.0.0`; do not set `HOST=127.0.0.1` |

The server runs in the **foreground**. If you close that terminal or press `Ctrl+C`, the app goes
straight back to this error. Keep it open for the whole session.

### `Error: listen EADDRINUSE: address already in use 0.0.0.0:8080`

A copy is already listening. Either use it — it is already serving — or free the port:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

```bash
pkill -f "node server.js"
```

`pkill -f "node server.js"` kills **every** instance, including ones on other ports. To kill just
one, use the PID from `lsof`: `kill <pid>`.

### Changing the port

`PORT=8081 npm start` only moves the server. The app's default is compiled in, so it keeps
calling 8080 and the error looks identical to a dead server. Change both or neither:

```kotlin
// automotive/build.gradle.kts, debug buildType
buildConfigField("String", "DEFAULT_CATALOG_BASE_URL", "\"http://10.0.2.2:8081\"")
```

Then rebuild. Staying on 8080 is simpler.

### Testing on a real device instead of an emulator

Two extra steps beyond the emulator flow:

1. Use the `wifi` address the server prints at startup, not `10.0.2.2` — that alias only exists
   inside an emulator. Set it as `DEFAULT_CATALOG_BASE_URL` and rebuild.
2. Add that IP to [network_security_config.xml](automotive/src/main/res/xml/network_security_config.xml).
   Only `10.0.2.2`, `localhost` and `127.0.0.1` are allowed cleartext; any other host is blocked
   over plain HTTP and fails with a `CLEARTEXT communication not permitted` error.

Both machines must be on the same network, and macOS may prompt to allow incoming connections
the first time.

### Every row says `Install` even though the apps are installed

Package visibility filtering (API 30+). Confirm `QUERY_ALL_PACKAGES` is present in
`AndroidManifest.xml` and actually granted — without it `getPackageInfo` throws
`NameNotFoundException` for an app that is really there. Verify what the device actually has:

```bash
adb shell dumpsys package com.lucid.auto.media | grep -m1 versionName
```

### Tapping `Install` does nothing, or the install fails immediately

An unprivileged build needs the per-app unknown-sources permission:

```bash
adb shell appops set com.lucid.auto.appstore REQUEST_INSTALL_PACKAGES allow
```

### A row stays on `Update` after a successful install

You used `POST /admin/bump/:pkg`, which changes only what the catalogue *claims*; the APK on disk
still declares the old `versionCode`, so the installed version never catches up. Publish a
genuinely newer build instead:

```bash
./tools/publish-samples.sh --bump 5
```

### Auto-update is on but nothing updates

Check in order:

- The row must be in `Update` state. Auto-update never installs an app that is not already on the
  device — that is deliberate (FR-6.6).
- Cold-start the app; `adb shell am start` on an already-running instance only triggers a plain
  refresh, not the launch pass:

  ```bash
  adb shell am force-stop com.lucid.auto.appstore && adb shell monkey -p com.lucid.auto.appstore -c android.intent.category.LAUNCHER 1
  ```

- The periodic worker needs an **unmetered** network and battery-not-low, so it will not fire on a
  metered or low-battery emulator. The on-launch pass has no such constraints.
- Silent updating only applies to apps this store installed itself. An app installed with
  `adb install` still shows the confirmation dialog until the store has installed it once.

## Tests

`./gradlew :automotive:testDebugUnitTest` — 93 tests, no emulator. Compose UI tests run under
Robolectric.

| Suite | Covers |
|---|---|
| `AppStateResolverTest` | the full Install/Update/Installed truth table, including the `"1.10.0" < "1.9.0"` string-comparison trap |
| `AppItemTest` | version-line and action-label formatting per state |
| `PackageManagerInspectorTest` | local version reads against a shadowed `PackageManager` |
| `CatalogApiTest` | real HTTP + parsing against MockWebServer, forward compatibility, error paths |
| `ApkDownloaderTest` | progress, SHA-256 accept/reject, partial-file cleanup, path containment |
| `AppRepositoryTest` | local+remote merge, offline fallback, in-flight progress overlay |
| `UpdateCoordinatorTest` | download→install orchestration; auto-update touches only `Update` rows |
| `AppListViewModelTest` | launch states, tap handling, auto-update on/off, scheduler wiring |
| `AppListScreenTest` | rendered rows, disabled `Installed` control, switch, error banner |
| `AutoUpdateSchedulerTest` | unique periodic work enqueued/cancelled with the flag |

### Verified end-to-end

The flow was exercised on a Pixel 6 API 34 emulator against the local server:

- all three states rendered correctly side by side (Update / Installed / Install), with icons
  from `PackageManager` for installed apps and from the catalogue for Spotify;
- tapping **Update** downloaded the APK, showed the platform confirmation, installed, and the row
  settled on `Installed 1.1.5`;
- with auto-update on, a cold launch against a newer catalogue updated Lucid Media
  `1.1.5 → 1.1.8` **silently, with no dialog** — the store was the original installer, so
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION` applied. Lucid Radio, which had been installed via `adb`,
  correctly still required confirmation.

---

## Layout

```
automotive/             the LucidAppStore Android app (Gradle module :automotive)
  src/main/kotlin/...   data · domain · ui · update · di
  src/test/kotlin/...   unit + Compose tests, fakes
samples/app/            three tiny fixture APKs (product flavours)
server/                 Node + Express catalogue and APK host
tools/                  publish-samples.sh · apk-version.sh
system/                 privapp permission allowlist for the privileged build
docs/                   DELIVERY_PLAN.md (+ .docx) · REQUIREMENTS.md · ARCHITECTURE.md
```

---

## Delivery plan

The programme plan — team structure, responsibilities, interface contracts, security and rollback
requirements, risks, and a 6-week / 3-sprint schedule — lives in
[docs/DELIVERY_PLAN.md](docs/DELIVERY_PLAN.md).

`docs/LucidAppStore-Delivery-Plan.docx` is the same content as a Word document for sharing outside
the repo. It is generated, not hand-edited: change the Markdown, then regenerate.

---

## Known gaps

- **The catalogue cache is in-memory only.** A cold start with no network shows an empty list.
  In a car — parking garages, tunnels, no data plan — that is a realistic state. Persisting the
  last-good catalogue to disk is ~20 lines and would make the list survive a restart offline.
  Not built, because it was not asked for.
- **Self-update requires auto-update to be enabled**, since it runs only from the periodic
  worker. If the owner turns auto-update off, the store stops updating itself. Consistent with
  "no background installs unless opted in", but worth a deliberate decision.
- **No staged rollout or version floor.** A bad store build reaches every vehicle that polls.
  A `minStoreVersionCode` field in the catalogue would let the server refuse to serve app updates
  to a store too old to handle them.

---

## Out of scope (future)

1. A push-notification server telling the vehicle when new versions are published.
2. A production catalogue/version service — the Node server here is a local test harness only.
3. Uninstall, rollback, staged rollouts, entitlement, search.

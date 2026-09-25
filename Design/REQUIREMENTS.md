# Automotive App Store — Requirements

**Status:** Approved v2 (2026-09-25) · **Date:** 2026-09-25 · **Next step:** design ([DESIGN.md](DESIGN.md)), then code

This document lists what the app store must do. It does not say how. Design starts once these
requirements are approved. The decisions that shape the architecture are already resolved
(§10.1). The rest only set defaults and can be answered during design (§10.2).

---

## 1. Purpose and scope

An app store for the vehicle's Android Automotive OS (AAOS) head unit. It lets the vehicle
discover, download, install, update and uninstall apps, and download content that belongs to
those apps. A backend controls which versions each vehicle receives, how fast a release rolls
out, and how urgent each installation is.

**In scope**

| Area | Summary |
|---|---|
| Vehicle client | Catalogue, APK download, install, update, uninstall, content download, analytics |
| Network behaviour | Wi-Fi-only, cellular, intermittent network, resumable downloads |
| Integrity | Checksum and signature checks before anything is installed or activated |
| Server | Version management, phased rollouts, installation types (forced / manual / critical), targeting, analytics |

**Out of scope for this document** (candidates for later phases): payments and paid apps,
ratings and reviews, third-party developer self-publishing, search ranking and recommendations,
the phone companion app, updates to the OS itself.

### 1.1 Decisions carried forward from earlier work

These were decided earlier and are treated as constraints unless you reopen them.

| # | Decision | Effect on requirements |
|---|---|---|
| C1 | The store is a native Android app, not a WebView | A browser cannot read installed versions or install APKs |
| C2 | The store app itself is delivered by the vehicle OTA system and never updates itself | The store does not appear in its own catalogue |
| C3 | Store policy (feature flags, kill switch) comes from our own backend policy endpoint (D3) | Policy changes need no new store build |
| C4 | Each app row shows only icon, title, version and state, with a fixed three-word status vocabulary, and never a failure reason | Failure detail goes to analytics, not the screen |

---

## 2. Actors

| Actor | Description |
|---|---|
| **Driver / occupant** | Uses the store on the head unit. May be parked or driving. |
| **Vehicle** | The head unit, its connectivity (Wi-Fi client, cellular modem), power state and storage. |
| **Release manager** | Publishes app versions and content, starts, pauses and halts rollouts. |
| **Fleet operator / support** | Views rollout health and per-vehicle state; triggers critical updates. |
| **Analyst** | Reads download, install and usage analytics. |
| **App developer (internal)** | Supplies signed APKs and content packages. |

---

## 3. Glossary

| Term | Meaning |
|---|---|
| **App** | An Android package identified by its package name. |
| **Release** | One version of an app: a `versionCode`, its APK artifact(s), metadata and rules. |
| **Content package** | Data an app needs that is not in its APK: maps, voice packs, media, models, themes. Versioned separately from the app. |
| **Artifact** | Any downloadable file: an APK, split APK set, or content package. |
| **Manifest** | Server response telling one vehicle what it should have. Signed by the server. |
| **Installation type** | How an update is applied: *manual*, *forced* or *critical* (§6.3). |
| **Rollout** | Controlled delivery of a release to a growing share of eligible vehicles. |
| **Safe window** | A moment when installing will not disrupt the driver (§5.8). |
| **Unmetered network** | A network Android reports as not metered. A Wi-Fi hotspot shared from a phone is metered. |

---

## 4. Priority key

**M** = Must for first release · **S** = Should, first release if time allows · **C** = Could, later.
Every requirement has an ID so design, code and tests can trace back to it.

---

## 5. Vehicle client requirements

### 5.1 Catalogue and discovery

| ID | Requirement | P |
|---|---|---|
| CAT-01 | Show the list of apps available to this vehicle, as decided by the server manifest. | M |
| CAT-02 | Show for each app whether it is not installed, installed, or has an update available, by comparing the installed `versionCode` with the manifest. | M |
| CAT-03 | Show an app's detail: description, size, version, release notes, content packages. | M |
| CAT-04 | Cache the last good manifest on disk so the store opens and shows state with no network. | M |
| CAT-05 | Refresh the manifest on store launch, on network regained, and on a periodic schedule set by server policy. | M |
| CAT-06 | Reject a manifest whose signature does not verify, and keep using the cached one. | M |
| CAT-07 | Filter the list by category and by installed / updates available. | S |

### 5.2 APK download

| ID | Requirement | P |
|---|---|---|
| DL-01 | Download the APK for a release to app-private storage. | M |
| DL-02 | Support single APKs and split APK sets. | S |
| DL-03 | Check free storage before starting; do not start if the artifact plus install overhead would take the device below a reserved floor. | M |
| DL-04 | Show download progress (bytes and percent) on the app's detail page and in a notification. | M |
| DL-05 | Allow the user to pause and cancel a manual download. Forced and critical downloads may not be cancelled. | M |
| DL-06 | Queue downloads; run a limited number at once (default 1–2, server-configurable). Critical items jump the queue. | M |
| DL-07 | Continue downloading when the store UI is closed, and when the vehicle is in drive. | M |
| DL-08 | Delete downloaded artifacts after successful install, after cancel, or after they expire. | M |
| DL-09 | Download from a CDN using short-lived signed URLs issued by the server. | M |

### 5.3 Network policy — Wi-Fi only and cellular

| ID | Requirement | P |
|---|---|---|
| NET-01 | Classify the current network as **unmetered** (Wi-Fi without metering), **metered** (cellular, phone hotspot, any Wi-Fi reported as metered) or **roaming**. Use Android's reported network capabilities, not the transport type alone. | M |
| NET-02 | By default, download apps and content only on unmetered networks ("Wi-Fi only"). | M |
| NET-03 | Provide a user setting to allow downloads on cellular. | M |
| NET-04 | The server can set per release and per content package whether cellular is allowed, and a maximum size allowed on cellular. | M |
| NET-05 | The effective rule is the **stricter** of user setting and server rule, **except** critical updates, which may use cellular even when the user chose Wi-Fi only (subject to NET-07). | M |
| NET-06 | When a download is waiting for Wi-Fi, show it as waiting, not failed. | M |
| NET-07 | Do not download on roaming networks, except critical updates when server policy allows it. | M |
| NET-08 | If the network changes from unmetered to metered mid-download and the item is not allowed on metered, pause and wait. Resume automatically when allowed again. | M |
| NET-09 | Enforce a monthly cellular data budget for the store, set by server policy. Stop non-critical cellular downloads when it is reached. | S |
| NET-10 | Record bytes transferred per network type for analytics. | M |

### 5.4 Intermittent network

| ID | Requirement | P |
|---|---|---|
| INT-01 | Treat loss of connectivity as a pause, never as a failure. | M |
| INT-02 | Retry transient errors (timeouts, connection resets, HTTP 5xx, 429) with exponential backoff and jitter. Proposed default: start 5 s, cap 15 min. Honour `Retry-After`. | M |
| INT-03 | Resume automatically when connectivity returns, without user action. | M |
| INT-04 | Fail an item permanently only on non-transient errors (HTTP 4xx other than 408/429, checksum mismatch after retries, out of storage, release withdrawn). | M |
| INT-05 | Limit total retries per item per day (server-configurable), then wait until the next day or next manifest refresh. | M |
| INT-06 | Detect a stalled transfer (no bytes for a set time, proposed 30 s) and reconnect. | M |
| INT-07 | Refresh an expired signed download URL without restarting the download. | M |
| INT-08 | Tolerate slow links: no fixed total download timeout, only stall detection. | M |

### 5.5 Resuming downloads

| ID | Requirement | P |
|---|---|---|
| RES-01 | Resume a partial download from the last byte received, using HTTP range requests. | M |
| RES-02 | Only resume if the server file is unchanged (ETag or equivalent validator matches). Otherwise discard and restart. | M |
| RES-03 | Persist download state (item, bytes received, validator) so it survives app process death, ignition off and head unit reboot. | M |
| RES-04 | After a reboot or ignition cycle, resume pending downloads automatically once network policy allows. | M |
| RES-05 | If the server does not support range requests for an artifact, restart it from zero and report this to analytics. | M |
| RES-06 | Verify large artifacts in chunks (per-chunk hashes in the manifest) so a corrupt chunk can be re-fetched without restarting the whole file. | S |
| RES-07 | Discard partial downloads older than a set age (proposed 14 days) or for releases no longer offered. | M |

### 5.6 Integrity — checksum and signature before install

| ID | Requirement | P |
|---|---|---|
| INTG-01 | The manifest gives, for every artifact, its size and SHA-256 hash. The manifest itself is signed by the server. | M |
| INTG-02 | After download completes, check the size and SHA-256 hash against the manifest. **No artifact is installed or activated unless both match.** | M |
| INTG-03 | On mismatch: delete the file, re-download from scratch, and after a set number of failures (proposed 3) mark the item failed and report it. | M |
| INTG-04 | Before installing an APK, check that its signing certificate matches the certificate digest in the manifest. For an update, it must also match the certificate of the installed app. | M |
| INTG-05 | Check the APK's package name and `versionCode` match the manifest entry. | M |
| INTG-06 | Re-check the hash immediately before handing the file to the installer, to close the gap between verify and install. | M |
| INTG-07 | Store downloads only in app-private storage that other apps cannot write to. | M |
| INTG-08 | Refuse to install a `versionCode` lower than the installed one (no downgrades). | M |
| INTG-09 | Apply the same hash checks to content packages before they are activated. | M |

### 5.7 Install and update

| ID | Requirement | P |
|---|---|---|
| INS-01 | Install a verified APK using Android's package installer session API. | M |
| INS-02 | Report the install result (success, or failure with Android's reason code) to analytics. | M |
| INS-03 | Manual installs: the user starts the install. | M |
| INS-04 | Forced and critical installs happen without the user starting them, in a safe window (§5.8). No system install prompt is shown (the store is privileged, D1). | M |
| INS-05 | Do not install an update to an app while that app is in the foreground or providing an active service the driver relies on (navigation, media playback, a call). Wait for the next safe window. | M |
| INS-06 | After an update, check the installed `versionCode` equals the target and report it. | M |
| INS-07 | If an install fails, keep the previously installed version working. | M |
| INS-08 | Install any required content packages before or with the app, so the app is never left without content it needs. | M |
| INS-09 | Install every app for all driver profiles on the vehicle, not only the profile in use (D4). Uninstall likewise removes it for all profiles. | M |
| INS-10 | A driver profile added later has every app the store has installed, without downloading again. | M |

### 5.8 Vehicle state and safe windows

| ID | Requirement | P |
|---|---|---|
| VEH-01 | Follow Android Automotive driving restrictions: while driving, limit the store UI to what the platform allows (no long text, no typing). | M |
| VEH-02 | Downloads may run while driving. User-visible installs and uninstalls happen only when parked, unless the app being changed is not in use. | M |
| VEH-03 | Do not start installs when battery or state of charge is below a server-set threshold. | M |
| VEH-04 | Use the vehicle's post-ignition-off maintenance window (AAOS Garage Mode) as a safe window for forced and critical installs. | S |
| VEH-05 | Never leave an install half-finished because of power loss; on next boot, detect and report the outcome. | M |
| VEH-06 | Keep head unit performance acceptable while downloading: limit CPU, I/O and bandwidth use so navigation and media are not affected. | M |

### 5.9 Uninstall

| ID | Requirement | P |
|---|---|---|
| UNI-01 | Let the user uninstall an app the store installed, when parked. | M |
| UNI-02 | Ask the user to confirm before uninstalling. | M |
| UNI-03 | Uninstalling an app also removes its content packages and any pending downloads for it. | M |
| UNI-04 | The server can mark an app as not removable (for example, a forced or critical app). The store then hides the uninstall action. | M |
| UNI-05 | Pre-installed system apps cannot be uninstalled; only their updates can be removed, if the platform allows. | S |
| UNI-06 | The server can order a remote uninstall or disable of an app (recall or kill switch). | M |
| UNI-07 | Report every uninstall and its source (user or server) to analytics. | M |

### 5.10 Content download

| ID | Requirement | P |
|---|---|---|
| CON-01 | Download content packages listed in the manifest for an installed app. | M |
| CON-02 | Content packages follow the same network (§5.3), retry (§5.4), resume (§5.5) and integrity (§5.6) rules as APKs. | M |
| CON-03 | Each content package declares the app version range it works with. Do not install content an installed app cannot use. | M |
| CON-04 | Activate content atomically: download to staging, verify, then switch over. The app never sees a half-written package. | M |
| CON-05 | Deliver content to the owning app through a defined handoff (to be designed), not a shared world-readable folder. | M |
| CON-06 | Support content marked required (installed with the app) and optional (the user chooses, for example extra voice packs). | M |
| CON-07 | Support delta updates for large content (download only what changed). | C |
| CON-08 | Remove content packages that are superseded or whose app is gone. | M |

### 5.11 User-facing states and notifications

| ID | Requirement | P |
|---|---|---|
| UX-01 | Show each app in one state from a fixed set: for example *Get*, *Update*, *Installed*, *Waiting for Wi-Fi*, *Downloading*, *Installing*. Final vocabulary set by UX (C4). | M |
| UX-02 | Tell the user when a forced or critical update will be applied, and when it was applied. | M |
| UX-03 | Show a short, fixed message for failures (for example "Couldn't update. Will retry."). Detailed reasons go to analytics only (C4). | M |
| UX-04 | Let the user see and change the Wi-Fi-only / cellular setting. | M |
| UX-05 | Notifications follow AAOS notification rules while driving. | M |

---

## 6. Server requirements

### 6.1 Version management

| ID | Requirement | P |
|---|---|---|
| VER-01 | Store every release of every app: `versionCode`, `versionName`, artifacts, hash, size, signing certificate digest, release notes, creation date and author. | M |
| VER-02 | Artifacts are immutable once published. A fix is a new release with a higher `versionCode`. | M |
| VER-03 | Reject an upload whose package name, `versionCode` or signing certificate does not match the app record, or whose `versionCode` is not higher than the latest release. | M |
| VER-04 | Each release declares compatibility: minimum and maximum OS/API level, vehicle model, hardware variant, region, and minimum store version. | M |
| VER-05 | Set a **minimum supported version** per app. Vehicles below it get a forced update. | M |
| VER-06 | Release channels (for example internal, beta, production). A vehicle is assigned to one channel. | M |
| VER-07 | Withdraw a release: stop offering it; vehicles that have not installed it stop downloading it. | M |
| VER-08 | Roll back by publishing the previous good build again with a higher `versionCode` (Android does not allow downgrades without platform privilege). The tooling should make this a one-step action. | M |
| VER-09 | Version content packages the same way, with their compatible app version range. | M |
| VER-10 | Keep a full audit log of who changed what, when. | M |

### 6.2 Phased rollouts

| ID | Requirement | P |
|---|---|---|
| ROL-01 | Roll out a release to a percentage of eligible vehicles, then increase it in steps (for example 1% → 5% → 25% → 50% → 100%). | M |
| ROL-02 | A vehicle's place in a rollout is decided by a stable hash of its device ID and the app, so it does not move in and out of the rollout between requests, and the same vehicles are not always first for every app. | M |
| ROL-03 | Target a rollout to a named cohort (allowlist of vehicles, test fleet, employee cars) before or alongside percentages. | M |
| ROL-04 | Target by vehicle model, hardware variant, OS version, region and channel. | M |
| ROL-05 | Pause a rollout (no new vehicles start) and resume it. | M |
| ROL-06 | Halt a rollout (stop offering the release entirely) in one action. | M |
| ROL-07 | Show rollout health live: vehicles offered, downloaded, verified, installed, failed, and failure rate by reason. | M |
| ROL-08 | Automatically pause a rollout when the failure rate or crash rate crosses a set threshold. | S |
| ROL-09 | Schedule rollout steps in advance (for example, next step after 48 h if healthy). | S |
| ROL-10 | Allow only one active rollout per app per channel at a time. | M |

### 6.3 Installation types

Each release (and each content package) carries exactly one installation type.

| Type | Meaning | Download | Install | User can decline? |
|---|---|---|---|---|
| **Manual** | Normal update or new app. | When the user asks (or auto-update is on), under normal network rules. | When the user taps, or automatically if auto-update is on. | Yes |
| **Forced** | Must be installed within a deadline, for example a new minimum supported version. | Automatically, under normal network rules (Wi-Fi only by default). | Automatically in the next safe window. The user may postpone up to a set number of times or until the deadline. | No, but may postpone |
| **Critical** | Security or safety fix, urgent. | Immediately, top of the queue, cellular allowed even if the user chose Wi-Fi only (NET-05), roaming only if the server allows. | At the first safe window. No postponing. | No |

| ID | Requirement | P |
|---|---|---|
| TYP-01 | Set the installation type per release, and change it after publishing (for example, raise a forced update to critical). | M |
| TYP-02 | For forced updates, set a deadline and a maximum number of postponements. | M |
| TYP-03 | After a forced update's deadline, or for any critical update, the server can block launching the old version until it is updated. The privileged store can do this by suspending the package. Whether to block or only warn is D7. | S |
| TYP-04 | Critical updates can be targeted to all affected vehicles at once, skipping phased rollout, with an explicit confirmation step in the console. | M |
| TYP-05 | Push a "check now" signal to vehicles for critical updates rather than waiting for the next scheduled refresh. | S |
| TYP-06 | Report, per forced and critical release, how many vehicles are still not updated and why. | M |

### 6.4 Manifest and API

| ID | Requirement | P |
|---|---|---|
| API-01 | One endpoint returns the signed, per-vehicle manifest: apps, releases, content packages, installation types, network rules, rollout decisions. | M |
| API-02 | Authenticate every vehicle with a per-device credential (for example a client certificate from the vehicle's secure element). | M |
| API-03 | All traffic over TLS. | M |
| API-04 | Support conditional requests so an unchanged manifest costs almost no data. | M |
| API-05 | Issue short-lived signed CDN URLs for artifacts; the CDN supports range requests and validators (for RES-01/02). | M |
| API-06 | Version the API; old store versions in the field keep working for a defined period. | M |

### 6.5 Publishing console

| ID | Requirement | P |
|---|---|---|
| PUB-01 | Upload a signed APK or content package and create a release without editing files by hand. | M |
| PUB-02 | Compute the hash and read package name, `versionCode` and certificate from the upload; do not trust typed values. | M |
| PUB-03 | Role-based access: who can upload, who can start rollouts, who can mark critical. | M |
| PUB-04 | Require a second person to approve a critical release or a 100% rollout. | S |

---

## 7. Analytics requirements

### 7.1 Events the vehicle reports

| ID | Event | Key properties |
|---|---|---|
| AN-01 | `catalog_viewed`, `app_detail_viewed` | app, source screen |
| AN-02 | `download_queued`, `download_started`, `download_paused`, `download_resumed`, `download_completed`, `download_failed`, `download_cancelled` | app, release, content package, installation type, network type, bytes this session, total bytes, resume offset, retry count, duration, reason code |
| AN-03 | `network_wait` | reason (waiting for Wi-Fi, roaming, data budget), duration |
| AN-04 | `checksum_verified`, `checksum_failed`, `signature_failed` | app, release, attempt number |
| AN-05 | `install_started`, `install_succeeded`, `install_failed`, `install_deferred` | app, from version, to version, installation type, Android status code, defer reason (driving, app in use, low battery) |
| AN-06 | `uninstall` | app, version, source (user / server) |
| AN-07 | `content_activated`, `content_failed` | package, version, app |
| AN-08 | `forced_update_postponed` | app, postponements used, deadline |
| AN-09 | `manifest_fetched`, `manifest_rejected` | result, bytes, from cache |
| AN-10 | `setting_changed` | setting (cellular allowed), old, new |

Every event also carries: pseudonymous device ID, store version, OS version, vehicle model,
hardware variant, region, channel, event time (device clock) and a sequence number.

### 7.2 Delivery and privacy

| ID | Requirement | P |
|---|---|---|
| AN-11 | Buffer events on disk when offline and upload in batches later. Nothing is lost across reboots, up to a size cap (oldest dropped first, drop count reported). | M |
| AN-12 | Prefer uploading on unmetered networks; allow small batches on cellular. | M |
| AN-13 | No personal data in events. The vehicle ID (VIN) is never sent in analytics; use a pseudonymous ID. | M |
| AN-14 | Respect the vehicle's data-sharing consent setting; if analytics consent is off, send only what is legally required for safety and security updates. | M |
| AN-15 | The server can change the sampling rate and turn event types on or off without a store update. | S |

### 7.3 Server dashboards

| ID | Requirement | P |
|---|---|---|
| AN-16 | Rollout funnel per release: offered → download started → downloaded → verified → installed → first launch. | M |
| AN-17 | Failure breakdown by reason, vehicle model, OS version and network type. | M |
| AN-18 | Data usage: bytes by network type, share on cellular, resume savings (bytes not re-downloaded). | M |
| AN-19 | Time to reach X% of fleet for forced and critical releases. | M |
| AN-20 | Install base: vehicles per app per version. | M |

---

## 8. Scenario acceptance criteria

These are the scenarios you called out. Each becomes a test case during design.

### S1 — Downloading on Wi-Fi only

| Given | When | Then |
|---|---|---|
| Setting is Wi-Fi only, vehicle on cellular | User taps Get on a manual app | Item shows *Waiting for Wi-Fi*; no bytes sent on cellular |
| Same | Vehicle joins home Wi-Fi (unmetered) | Download starts without user action |
| Downloading on Wi-Fi | Vehicle drives away, Wi-Fi drops, cellular available | Download pauses, stays queued, resumes on next unmetered Wi-Fi from the last byte |
| Setting is Wi-Fi only, vehicle on a phone hotspot (metered Wi-Fi) | Any non-critical download | Treated as cellular; waits |

### S2 — Downloading on cellular

| Given | When | Then |
|---|---|---|
| User allowed cellular; release allows cellular; size under the cellular cap | User taps Get | Downloads on cellular |
| User allowed cellular; release exceeds cellular size cap | User taps Get | Waits for Wi-Fi; user told why in fixed wording |
| User chose Wi-Fi only | Critical update published | Downloads on cellular anyway |
| Vehicle roaming | Non-critical download | Waits; no roaming data used |
| Monthly cellular budget reached | Non-critical download | Waits for Wi-Fi |

### S3 — Intermittent network

| Given | When | Then |
|---|---|---|
| Download in progress | Connection drops for 10 s, 2 min, 2 h | Pauses, retries with backoff, resumes from last byte; never shown as failed |
| Download in progress | Bytes stop flowing but the socket stays open | Stall detected within the stall timeout; reconnects |
| Signed URL expires during an outage | Connection returns | New URL fetched; download resumes, not restarted |
| Server returns 503 with `Retry-After` | — | Waits at least that long before retrying |
| Server returns 404 (release withdrawn) | — | Item fails permanently, partial file deleted, reported |

### S4 — Resuming download

| Given | When | Then |
|---|---|---|
| 60% downloaded | Store process killed | On restart, continues from 60% |
| 60% downloaded | Ignition off, then on next day | Continues from 60% once network policy allows |
| 60% downloaded | Server artifact changed (validator differs) | Discards partial and restarts |
| Partial download 20 days old | Cleanup runs | Partial deleted |
| CDN ignores range request | Resume attempted | Restarts from zero; reported |

### S5 — Checksum validation before install

| Given | When | Then |
|---|---|---|
| Download complete, hash matches, cert matches | — | Install proceeds (subject to safe window) |
| Download complete, hash mismatch | — | File deleted, re-downloaded; after 3 failures item fails and `checksum_failed` is reported. Nothing installed. |
| Hash matches but signing certificate differs from manifest or installed app | — | Not installed; `signature_failed` reported |
| File altered on disk after verification | Just before install | Re-check catches it; not installed |
| Manifest signature invalid | Manifest fetched | Manifest rejected; cached manifest kept |

---

## 9. Non-functional requirements

| ID | Area | Requirement | P |
|---|---|---|---|
| NFR-01 | Safety | Nothing the store does may distract the driver or interrupt navigation, calls or media. | M |
| NFR-02 | Security | Threat model and security review before any vehicle outside the test fleet. | M |
| NFR-03 | Security | All artifacts signed, all transport over TLS, per-device authentication (§5.6, §6.4). | M |
| NFR-04 | Regulatory | Confirm whether app updates fall under UNECE R155 (cybersecurity) and R156 / ISO 24089 (software update management) for our markets; if so, meet their record-keeping and approval rules. | M |
| NFR-05 | Privacy | Analytics compliant with GDPR / CCPA and the vehicle's consent settings. | M |
| NFR-06 | Reliability | No state in which a failed or interrupted install leaves an app unusable. | M |
| NFR-07 | Performance | Store opens to a usable list in under 2 s from cache (proposed). | S |
| NFR-08 | Performance | Downloading uses at most a set share of bandwidth and CPU (values set during design). | M |
| NFR-09 | Storage | Keep a reserved free-space floor (proposed 500 MB, server-configurable). | M |
| NFR-10 | Scale | Backend handles the full fleet refreshing manifests, plus a critical update to all vehicles within a set time (target to be agreed). | M |
| NFR-11 | Availability | Manifest API availability target (proposed 99.9%). Vehicles work offline from cache when it is down. | M |
| NFR-12 | Testability | Every scenario in §8 has an automated test; network conditions can be simulated on a bench. | M |
| NFR-13 | Observability | Server logs and metrics for every manifest request and download URL issued. | M |

---

## 10. Decisions

### 10.1 Resolved (2026-09-25)

| # | Decision | Answer | Effect |
|---|---|---|---|
| **D1** | Is the store a privileged system app? | **Yes.** It is signed with the platform key and allow-listed in the privapp permissions file. | Forced and critical installs happen without a prompt (INS-04). Remote uninstall is possible (UNI-06, now **M**), and so is blocking an old version (TYP-03). This replaces the earlier plan for an unprivileged MVP. |
| **D2** | What runs downloads in the background and resumes them after reboot? | **WorkManager, plus our own range downloader.** | Brings back a background scheduler for downloads only. The earlier plan had removed it in favour of remote config. Network constraints, retries and resume after reboot come from WorkManager. Resume, ETag checks and chunk hashes come from our downloader. |
| **D3** | Does the head unit have Google Play services? | **No, or unknown. We build our own backend.** | No Firebase. Our backend serves policy (C3), the "check now" signal (TYP-05) and analytics ingestion (§7). |
| **D4** | Per-profile or per-vehicle installs? | **Every app is installed for all driver profiles.** | INS-09 and INS-10. |

### 10.2 Still open (they set defaults but do not block design)

| # | Decision | Why it matters | Options |
|---|---|---|---|
| **D5** | Which content types are in scope first? | Affects size limits, delta support and the app handoff (CON-05). | Maps, voice packs, media, models, other |
| **D6** | Who pays for cellular data, and what are the default cellular size cap and monthly budget? | Sets NET-04, NET-09 defaults. | Numbers from product / business |
| **D7** | Should old versions be blocked from launching after a forced deadline or for critical updates (TYP-03)? | Since D1, blocking is technically possible by suspending the package. What is left is a product and legal choice. | (a) Block · (b) Warn only |
| **D8** | Is the catalogue first-party only? | Third-party apps add legal, review and signing work. | (a) First-party only · (b) Include third party |
| **D9** | Which markets? | Decides whether R155/R156 apply (NFR-04) and privacy rules. | List of regions |

---

## 11. Traceability to the original request

| You asked for | Covered by |
|---|---|
| Download an APK | §5.2 DL-01…09, §5.7 |
| Uninstall an APK | §5.9 UNI-01…07 |
| Download content | §5.10 CON-01…08 |
| Analytics | §7 AN-01…20 |
| Wi-Fi only | §5.3 NET-01…08, S1 |
| Cellular | §5.3 NET-03…10, S2 |
| Intermittent network | §5.4 INT-01…08, S3 |
| Resuming download | §5.5 RES-01…07, S4 |
| Checksum before install | §5.6 INTG-01…09, S5 |
| Server: version management | §6.1 VER-01…10 |
| Server: phased rollouts | §6.2 ROL-01…10 |
| Server: forced / manual / critical | §6.3 TYP-01…06 |

---

## 12. Approval

| Role | Name | Decision | Date |
|---|---|---|---|
| Product owner | | | |
| Engineering lead | | | |
| Security | | | |

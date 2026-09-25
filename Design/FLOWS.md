# Automotive App Store — Flow Diagrams

**Date:** 2026-09-25 · **Goes with:** [DESIGN.md](DESIGN.md) and [REQUIREMENTS.md](REQUIREMENTS.md)

Each flow shows the steps and decisions in order. How to read them:

- Rounded boxes are where a flow starts or ends.
- Diamonds are decisions.
- **↻** means the flow goes back to an earlier step, named in the box.
- **Wait ↻** means the store checks again when something changes: the network returns, the app
  closes, the car parks, or Garage Mode starts after ignition-off.

Rendered copies, in PNG and SVG, are in [diagrams/flows/](diagrams/flows/).

| # | Flow | Covers | Design |
|---|---|---|---|
| 1 | [End to end](#1-end-to-end) | From upload to install to rollout health | §1 |
| 2 | [Choosing a network](#2-choosing-a-network) | S1 Wi-Fi only, S2 cellular, roaming | §5.1–5.3 |
| 3 | [Download, drops and resume](#3-download-drops-and-resume) | S3 intermittent network, S4 resume | §5.4–5.5 |
| 4 | [Checks before install](#4-checks-before-install) | S5 checksum validation | §6.1 |
| 5 | [Install types at a glance](#5-install-types-at-a-glance) | Manual, forced, critical | §4.4, §9.5 |
| 6 | [Install](#6-install) | When an install may run, and what it does | §6.2–6.4 |
| 7 | [Uninstall](#7-uninstall) | By the user or by the server | §6.5 |
| 8 | [Content download](#8-content-download) | Staging, checks, activation | §7 |
| 9 | [Analytics](#9-analytics) | From event to dashboard | §10 |
| 10 | [Publishing](#10-publishing) | Version management | §9.6 |
| 11 | [Phased rollout](#11-phased-rollout) | Steps, auto-pause, halt | §9.4 |
| 12 | [Which release a vehicle gets](#12-which-release-a-vehicle-gets) | The rollout decision for one vehicle | §9.3 |

---

## 1. End to end

The whole path, from a release manager's upload to the app installed in the car and the rollout's
next step.

```mermaid
flowchart TD
    A(["Release manager uploads an APK"]) --> B["Server checks it and computes hashes"]
    B --> C{"Upload valid?"}
    C -->|no| C1(["Rejected"])
    C -->|yes| D["Draft release:<br/>set install type and targeting"]
    D --> E["Rollout: test cohort first,<br/>then 1% up to 100%"]
    E --> F["Vehicle fetches its signed manifest"]
    F --> G{"Signature, vehicle ID<br/>and sequence OK?"}
    G -->|no| G1(["Keep the cached manifest<br/>and report it"])
    G -->|yes| H["Compare with what is installed"]
    H --> I{"Anything to do?"}
    I -->|no| I1(["Nothing"])
    I -->|yes| J["Queue a work item,<br/>ordered by install type"]
    J --> K["Download when network rules allow;<br/>resume after drops"]
    K --> L{"Size, SHA-256, signer,<br/>package and version OK?"}
    L -->|no| L1(["Download again, or fail<br/>and report"])
    L -->|yes| M["Wait for a safe window"]
    M --> N["Install for every driver profile"]
    N --> O["Send events and inventory"]
    O --> P["Rollout health on the dashboards"]
    P --> Q{"Too many failures?"}
    Q -->|yes| Q1(["Pause the rollout"])
    Q -->|no| Q2(["Next rollout step"])
```

## 2. Choosing a network

How the store decides whether a download may use the current network. This covers S1 (Wi-Fi only)
and S2 (cellular). A phone hotspot counts as metered, the same as cellular.

```mermaid
flowchart TD
    A(["Download is queued"]) --> K{"Kill switch on?"}
    K -->|yes| KW["Wait: kill switch"]
    K -->|no| B{"What network is up?"}
    B -->|none| W0["Wait: no network"]
    B -->|"Wi-Fi, not metered"| OK["Download on Wi-Fi"]
    B -->|"cellular or metered Wi-Fi"| C{"CRITICAL update?"}
    C -->|yes| OKC["Download on cellular"]
    C -->|no| D{"User allows cellular?"}
    D -->|no| WW["Wait for Wi-Fi"]
    D -->|yes| E{"Release allows cellular?"}
    E -->|no| WW
    E -->|yes| F{"Size within the cellular cap?"}
    F -->|no| WW
    F -->|yes| G{"Monthly data budget left?"}
    G -->|no| WW
    G -->|yes| OKC
    B -->|roaming| R{"CRITICAL, and the release<br/>allows roaming?"}
    R -->|yes| OKR["Download while roaming"]
    R -->|no| WR["Wait: roaming"]
    OK --> BIND(["The transfer is tied to this network.<br/>If it drops or changes:<br/>pause and decide again"])
    OKC --> BIND
    OKR --> BIND
```

## 3. Download, drops and resume

What the downloader does with each server response. This covers S3 (intermittent network) and S4
(resume). Losing the network is a pause, never a failure.

```mermaid
flowchart TD
    S(["Worker starts: first run, network back,<br/>or after a reboot"]) --> B{"Partial file on disk?"}
    B -->|no| C["GET from byte 0"]
    B -->|yes| D["GET from the last saved byte:<br/>Range + If-Range with the saved ETag"]
    C --> E{"Response?"}
    D --> E
    E -->|"206, same ETag"| F["Append bytes; check each 4 MB chunk<br/>and save progress after it"]
    F --> L{"What happened?"}
    L -->|"all bytes in"| V(["Go to the checks before install"])
    L -->|"no bytes for 30 s"| R1(["↻ Request again from<br/>the last saved byte"])
    L -->|"a chunk failed its hash"| L1["Cut that chunk off"]
    L1 --> R2(["↻ Request again from<br/>the last saved byte"])
    L -->|"network lost"| M["Worker stops. Bytes are safe on disk;<br/>not counted as a retry"]
    M --> N(["WorkManager starts the worker again<br/>when an allowed network returns"])
    E -->|"200, or 206 with a new ETag"| G["File changed or range ignored:<br/>discard the partial file"]
    G --> R3(["↻ Start again from byte 0"])
    E -->|416| Q{"File already complete?"}
    Q -->|yes| V2(["Go to the checks before install"])
    Q -->|no| R4(["↻ Start again from byte 0"])
    E -->|"401 or 403"| H["URL expired:<br/>get a new signed URL"]
    H --> R5(["↻ Request again from<br/>the last saved byte"])
    E -->|"404 or 410"| I{"API says the release<br/>was withdrawn?"}
    I -->|yes| I1(["FAILED; partial file deleted"])
    I -->|no| J
    E -->|"5xx, 408, 429, timeout"| J["Back off 5 s up to 15 min, with jitter;<br/>never sooner than Retry-After"]
    J --> K{"Daily attempt limit reached?"}
    K -->|no| R6(["↻ Request again from<br/>the last saved byte"])
    K -->|yes| K1(["Wait until tomorrow<br/>or the next manifest"])
```

## 4. Checks before install

Nothing is installed until every check passes. This covers S5 (checksum validation).

```mermaid
flowchart TD
    A(["All bytes received"]) --> B{"Size matches<br/>the manifest?"}
    B -->|no| R1["Delete the file,<br/>download again"]
    B -->|yes| C{"SHA-256 matches<br/>the manifest?"}
    C -->|no| D{"Third mismatch?"}
    D -->|no| R1
    D -->|yes| F1(["FAILED<br/>report checksum_failed"])
    C -->|yes| E{"APK signature valid,<br/>signer on the allow-list?"}
    E -->|no| F2(["FAILED, no retry<br/>report signature_failed;<br/>the server pauses the rollout"])
    E -->|yes| G{"Updating an<br/>installed app?"}
    G -->|yes| G1{"Same signer as<br/>the installed app?"}
    G1 -->|no| F2
    G1 -->|yes| H
    G -->|no| H{"Package name and<br/>versionCode match?"}
    H -->|no| F3(["FAILED"])
    H -->|yes| I{"Compared with the<br/>installed version?"}
    I -->|same| C1(["CANCELLED: already installed"])
    I -->|older| F3
    I -->|"newer, or not installed"| RD["READY"]
    RD --> S["At install time, stream the file<br/>into the installer and hash it again"]
    S --> T{"Hash still matches?"}
    T -->|no| R2["Abandon the session,<br/>download again"]
    T -->|yes| U(["Commit the install"])
```

## 5. Install types at a glance

What `MANUAL`, `FORCED` and `CRITICAL` mean for one release on one vehicle.

```mermaid
flowchart TD
    A(["A newer release reaches the vehicle"]) --> B{"Install type?"}
    B -->|MANUAL| M{"Auto-update on?"}
    M -->|no| M1(["Show Update.<br/>The user decides."])
    M -->|yes| M2(["Download under normal network rules;<br/>install at a safe window"])
    B -->|CRITICAL| C1["Download at once, top of the queue.<br/>Cellular allowed;<br/>roaming only if the server allows"]
    C1 --> C2(["Install at the first safe window.<br/>No postponing"])
    B -->|FORCED| F1["Download under normal network rules<br/>(Wi-Fi only by default)"]
    F1 --> F2["Install at a safe window.<br/>The driver may postpone<br/>a set number of times"]
    F2 --> F3{"Postponements used up,<br/>or deadline passed?"}
    F3 -->|no| F5(["The driver may postpone again ↻"])
    F3 -->|yes| F4(["Only 'Update now' is offered.<br/>The old version may be suspended (D7)"])
    Y(["A REQUIRED app is not installed"]) -->|"at least FORCED"| F1
    Z(["Installed version is below<br/>the minimum supported version"]) -->|"at least FORCED"| F1
```

## 6. Install

**When an install may run.** "Automatic" means an auto-update of a manual release, a `FORCED`
release or a `CRITICAL` release.

```mermaid
flowchart TD
    A(["Item READY"]) --> P{"Battery above minimum,<br/>or charging?"}
    P -->|no| W1(["Wait ↻"])
    P -->|yes| B{"Who asked for it?"}
    B -->|"the user tapped Install or Update"| U{"Driving, and the<br/>app is in use?"}
    U -->|yes| W2(["Wait ↻"])
    U -->|no| I1(["Install"])
    B -->|automatic| D{"Is the app in use?"}
    D -->|no| I2(["Install"])
    D -->|yes| E{"Parked?"}
    E -->|no| W3(["Wait ↻"])
    E -->|yes| F{"Install type?"}
    F -->|"MANUAL, auto-update"| W4(["Wait ↻"])
    F -->|FORCED| G{"Postponements left, and<br/>before the deadline?"}
    G -->|yes| P1{"Prompt:<br/>Update now, or Later"}
    P1 -->|"Update now"| I3(["Install"])
    P1 -->|Later| W5(["Use one postponement.<br/>Wait ↻"])
    G -->|no| P2{"Prompt:<br/>Update now"}
    F -->|CRITICAL| P2
    P2 -->|"Update now"| I4(["Install"])
    P2 -->|"not answered"| W6(["Wait ↻"])
```

**What an install does.**

```mermaid
flowchart TD
    A(["Install"]) --> B["Stream the file into the installer,<br/>hashing it again"]
    B --> H{"Hash still matches?"}
    H -->|no| X(["Abandon the session,<br/>download again ↻"])
    H -->|yes| C["Commit the session"]
    C --> V{"Installed version<br/>equals the target?"}
    V -->|yes| D["New app: add it to the<br/>other driver profiles"]
    D --> E(["DONE: tell the driver,<br/>report install_succeeded"])
    V -->|no| F{"Can retry?"}
    F -->|yes| G(["Back to READY ↻"])
    F -->|no| Z(["FAILED: report the reason"])
```

## 7. Uninstall

The user and the server start an uninstall differently, but both end the same way.

```mermaid
flowchart TD
    A1(["User taps Uninstall"]) --> B{"Parked?"}
    B -->|no| BX(["Not offered while driving"])
    B -->|yes| C{"App marked removable?"}
    C -->|no| CX(["No Uninstall button"])
    C -->|yes| D{"User confirms?"}
    D -->|no| DX(["Nothing changes"])
    D -->|yes| E["Cancel the app's pending downloads"]
    A2(["Manifest marks the app REMOVED"]) --> S{"Installed by the store,<br/>and not a system app?"}
    S -->|no| SX(["Left alone"])
    S -->|yes| W{"App in use?"}
    W -->|yes| WW(["Wait for a safe window ↻"])
    W -->|no| E
    E --> F["Uninstall in every driver profile;<br/>the last one removes the APK"]
    F --> G["Delete its content packages"]
    G --> H(["Report the uninstall,<br/>source: user or server"])
```

## 8. Content download

Maps, voice packs and other data an app needs outside its APK.

```mermaid
flowchart TD
    A(["Manifest lists content<br/>for an installed app"]) --> B{"Required, or optional<br/>and chosen by the user?"}
    B -->|no| BX(["Skip"])
    B -->|yes| C{"Works with the app's<br/>installed or target version?"}
    C -->|no| BX
    C -->|yes| D["Download under the same network,<br/>retry and resume rules as APKs"]
    D --> E{"Size, SHA-256 and<br/>chunk hashes match?"}
    E -->|"no, tries left"| R1(["↻ Download again"])
    E -->|"no, third time"| EX(["FAILED: report it"])
    E -->|yes| F{"Zip file?"}
    F -->|yes| G["Unpack into staging.<br/>Reject ../, absolute paths, symlinks.<br/>Stop if larger than declared"]
    F -->|no| H
    G --> H["Rename the staging folder into place<br/>in one atomic step"]
    H --> I["Mark it active"]
    I --> J["Tell the app in every profile"]
    J --> K["The app reads files through<br/>the store's content provider"]
    K --> L(["Older versions are deleted in Garage Mode<br/>once no installed app needs them"])
```

## 9. Analytics

From something happening in the car to a number on a dashboard.

```mermaid
flowchart TD
    A(["Something happens in the car:<br/>download, check, install, uninstall"]) --> B{"Data-sharing consent on,<br/>or a legally required event?"}
    B -->|no| BX(["Not recorded"])
    B -->|yes| C{"Health event?"}
    C -->|yes| E["Write to the queue on disk,<br/>with the next sequence number"]
    C -->|no| D{"Event type on,<br/>and sampled in?"}
    D -->|no| BX
    D -->|yes| E
    E --> F{"Queue full?"}
    F -->|yes| G["Drop the oldest events,<br/>count how many"]
    G --> H
    F -->|no| H{"Network?"}
    H -->|"not metered"| U["Upload a compressed batch"]
    H -->|"metered, not roaming"| M{"Health events, or a<br/>batch of 64 KB or less?"}
    M -->|yes| U
    M -->|no| MW["Hold for Wi-Fi,<br/>at most 24 h"]
    MW --> U
    H -->|"roaming or none"| NW(["Wait for a network ↻"])
    U --> S["Server: check the schema, store,<br/>drop duplicates"]
    S --> V["The car deletes the events<br/>the server confirmed"]
    S --> W["Dashboards: funnel, failures,<br/>data usage, install base"]
    W --> X(["Rollout health feeds auto-pause"])
```

## 10. Publishing

How a new version gets into the system. This covers version management. Every step is written to
the audit log.

```mermaid
flowchart TD
    A(["Upload an APK or content package"]) --> B["Read package name, versionCode<br/>and signer from the file itself"]
    B --> C{"Package registered?"}
    C -->|no| X(["Rejected"])
    C -->|yes| D{"Signed by one of<br/>the app's signers?"}
    D -->|no| X
    D -->|yes| E{"versionCode higher than<br/>every existing release?"}
    E -->|no| X
    E -->|yes| F["Compute size, SHA-256, chunk hashes.<br/>Store the file under its hash;<br/>it never changes"]
    F --> G["Draft release, and a draft rollout at 0%"]
    G --> H["Set install type, deadline,<br/>targeting, release notes"]
    H --> I{"CRITICAL, or straight to 100%?"}
    I -->|yes| J{"A second person approves?"}
    J -->|no| JX(["Stays a draft"])
    J -->|yes| K
    I -->|no| K(["Start the rollout,<br/>usually with a test cohort"])
```

## 11. Phased rollout

How a running rollout grows, pauses itself, or is stopped. The rollout controller runs this every
5 minutes.

```mermaid
flowchart TD
    A(["Every 5 min, for a RUNNING rollout"]) --> C{"Any signature failure<br/>in the last hour?"}
    C -->|yes| P["Pause automatically"]
    C -->|no| D{"At least 50 attempts, and failures<br/>or crashes above the threshold?"}
    D -->|yes| P
    D -->|no| E{"Next scheduled step due?"}
    E -->|no| R1(["↻ Check again in 5 min"])
    E -->|yes| F["Raise the percentage,<br/>e.g. 1 → 5 → 25 → 50 → 100%"]
    F --> G{"At 100%?"}
    G -->|no| R2(["↻ Check again in 5 min"])
    G -->|yes| H(["COMPLETED"])
    P --> Q{"Release manager decides"}
    Q -->|resume| R3(["↻ Check again in 5 min"])
    Q -->|halt| R["HALTED: the release leaves every manifest;<br/>downloads in progress stop"]
    R --> S(["Fix: publish a corrected build,<br/>or use fast rollback (16.3)"])
```

## 12. Which release a vehicle gets

What the server decides for each app each time a vehicle asks for its manifest. A vehicle's bucket is
a number from 0 to 9999, made from a hash of its ID and the app's package name. It never changes,
so a vehicle stays in a rollout once it is in. When no older release is left, the app gets nothing
new. The finished manifest is signed and sent to the vehicle.

```mermaid
flowchart TD
    A(["Vehicle asks for its manifest"]) --> B["For each app, take its newest<br/>active release"]
    B --> C{"Does the release fit the vehicle?<br/>model, hardware, region, OS, channel"}
    C -->|no| N1(["↻ Try the next older release"])
    C -->|yes| D{"Rollout state?"}
    D -->|COMPLETED| Y1(["Offer this release"])
    D -->|"HALTED or DRAFT"| N2(["↻ Try the next older release"])
    D -->|"RUNNING or PAUSED"| E{"Vehicle in the<br/>rollout's cohort?"}
    E -->|yes| Y2(["Offer this release"])
    E -->|no| F{"Vehicle's bucket for this app<br/>below the percentage?"}
    F -->|yes| Y3(["Offer this release"])
    F -->|no| N3(["↻ Try the next older release"])
```

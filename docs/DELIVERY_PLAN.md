# LucidAppStore — Delivery Plan

**Sprint plan · 3 sprints · 6 weeks · Internal draft**

A minimum viable on-vehicle app store, built in three two-week sprints, while Design, Legal,
Security and OTA work the long-lead blockers in parallel.

---

## Executive summary

**The ask.** Staff two build teams (Application, Cloud) for six weeks, plus part-time capacity
from Design, OTA, Security, QA and Legal to run an unblock track alongside them.

**What we get at week 6.** Two deliverables, not one:

1. A working MVP on developer hardware — a real catalogue service over authenticated TLS, apps
   installing and updating on real devices, staged rollout and a kill switch.
2. **A production plan with real dates**, informed by answers the unblock track brings back. This
   is the more valuable of the two, and it only exists if that track is genuinely staffed.

**What we do not get at week 6.** Anything gated on an external dependency with queue time:
platform signing key access, third-party distribution rights, an independent penetration test,
driver-distraction certification, customer vehicles. These are not compressible by working
harder.

**Why six weeks is credible.** Three scope decisions route the plan around the slowest
dependencies:

| Decision | Effect |
|---|---|
| The MVP runs **unprivileged** | Removes the platform signing key from the critical path |
| The catalogue is **first-party only** | Removes the distribution-rights blocker entirely |
| A hard **Must / Should / Won't** list | Makes the date mean something |

**The three things most likely to move the date.**

1. The catalogue API contract is not frozen by day 5.
2. Application or Cloud engineers turn out to be part-time or shared.
3. Firebase Remote Config proves unavailable on our head unit (see the Google Play services
   question in §6).

**Decisions needed before kickoff.** Three, in §7: confirm the unprivileged MVP, confirm the
first-party-only catalogue, and confirm the two build teams are dedicated.

---

## 1. Where we are today

A working single-developer prototype exists and runs end-to-end on an emulator. It is a credible
foundation and an unshippable product. Six weeks is enough to turn it into a real internal MVP —
provided we are precise about which parts already work and ruthless about what we are not
attempting yet.

### Prototype audit

| Capability | State | Notes |
|---|---|---|
| Local version detection | **Proven** | Reads `versionCode` via PackageManager, targeted per catalogue entry. Needs `QUERY_ALL_PACKAGES` because the catalogue is server-driven. |
| Install / update engine | **Proven** | PackageInstaller sessions, dual-mode: silent when privileged, system dialog otherwise. Verified on emulator both ways. |
| Auto-update flag | **Rework** | On-launch pass is proven. The scheduled background job is being replaced by Firebase Remote Config as the policy source; the WorkManager scheduler, worker and boot receiver all come out. |
| Remote Config integration | **Not started** | New. Needs a Firebase project, a parameter schema, a fetch/activate policy, and a decision on what wakes the app when it is closed. |
| Catalogue contract | **Draft** | Schema exists and works, but has never been reviewed by a backend owner. Freeze this first. |
| Store update path | **Decided** | The store APK ships in the system image and is updated by OTA. It does not update itself. The prototype's self-update code is removed. |
| Catalogue server | **Throwaway** | Local Node harness for developer testing. No auth, no TLS, no CDN, no fleet targeting. Not a starting point for production. |
| Security posture | **Not started** | SHA-256 payload check only. No transport auth, no APK signature verification, no rollback protection, no threat model. |
| Rollback | **Not started** | Nothing in the prototype can undo a bad install. |
| Staged rollout | **In scope** | Today every vehicle that polls gets every update. Percentage rollout is Sprint 3 scope, served from the catalogue. |
| UX / HMI | **Placeholder** | Functional layout, never reviewed against driver-distraction rules or Lucid's design system. |

### Three delivery tiers

Each kind of change has its own path and cadence. Keeping them separate is what stops the store's
release train becoming a bottleneck for everything else.

| What changes | Delivered by | Cadence | Examples |
|---|---|---|---|
| Catalogue content | Catalogue service | Any time | Which apps exist, versions, titles, icons |
| Store policy | Firebase Remote Config | Any time | Auto-update on/off, kill switch, cohorts |
| Store binary | **OTA** | OTA train | The store APK itself — UI, install logic, fixes |

The store's own release cadence is tied to the OTA train. That is affordable precisely because the
two tiers that change often need no new store APK.

### Minimum scope for the six weeks

Anything not on the **Must** list is out unless a sprint review says otherwise.

**Must.** Catalogue API v1, frozen and implemented · catalogue service on real hosting with TLS ·
per-device authentication · APK artifact hosting with integrity metadata · app fetches, resolves
state, installs and updates · auto-update driven by Remote Config, checked on launch · **APK
signature verification before install** · catalogue cached to disk so the car works offline · a
publishing path a release manager can run without editing JSON · install success/failure telemetry
· automated tests on dev devices.

**Should.** Staged rollout by percentage, computed in the catalogue against a stable per-device
bucket · server-side kill switch · roll-forward rollback, exercised once as a drill · cohort
allowlist · real-time Remote Config listener.

**Won't.** Privileged silent install · platform rollback framework · downgrade installs · FCM push
wake-up · customer or VIN-targeted vehicles · Firebase A/B Testing experiments · independent
penetration test · third-party apps · driver-distraction certification · production on-call.

---

## 2. Teams and ownership

Application and Cloud are the build teams. The rest run the unblock track — clearing the
dependencies that decide what happens after week 6.

| Team | Owns | Responsibilities |
|---|---|---|
| **Program Management** | Schedule, gates, dependency register, escalation | Critical path; release train; the day-5 API contract freeze; tracking the legal blocker to closure |
| **Product & UX Design** | HMI spec, flows, copy, distraction compliance | Store flows and the auto-update consent model; what may be shown and touched in motion; applying Lucid's design system |
| **Android Application** | The APK, its tests, its performance budget | Catalogue fetch, version resolution, download, install; Remote Config as policy source with safe baked-in defaults; offline behaviour; rollback of managed apps |
| **Cloud & Catalogue** | API, artifact hosting, publishing tooling, uptime | Authenticated catalogue service; artifact store and CDN; publishing console; staged rollout buckets and one-change halt; rollout observability |
| **OTA & Release Eng** | System image integration, signing, rollback | Delivery of the store APK itself in the system image; platform key custody and signing pipeline; recovery when a store build is bad |
| **Security & Compliance** | Threat model, sign-off gates, pen test | Threat model for a component that can install software on a vehicle; reviews transport, auth and artifact verification; regulatory position |
| **QA & Vehicle Validation** | Test strategy, bench rig, quality bar | Unit through in-vehicle; the failure matrix; device automation; defect triage |
| **Legal & Partnerships** *(advisory)* | The distribution-rights blocker | Distribution agreements; owner-facing terms for automatic installation; data-protection review of telemetry |

### How they work together

| Forum | Frequency | Attendees | Purpose |
|---|---|---|---|
| Contract sync | Daily, week 1 only | App · Cloud · PMO | Gets the API frozen by day 5. Stops the moment it is signed off. |
| Build stand-up | Daily, 15 min | App · Cloud · QA | Blockers only. One stand-up for both build teams so integration problems surface same-day. |
| Unblock review | Weekly | PMO · Design · OTA · Security · Legal | Progress on the six questions in §5. Chased, not discussed. |
| Sprint review | End of each sprint | All leads + sponsor | Demo against the written sprint goal. Scope changes decided here and nowhere else. |

---

## 3. Opening the store

The store is reached from vehicle **Settings**, not a launcher icon. What the owner sees depends
on whether the catalogue could be fetched — and there are three outcomes to design for, not two.

| Condition | Screen | Behaviour |
|---|---|---|
| No network | *"Connect to Wi-Fi to see available apps"* | No fetch attempted. Offer a shortcut into Wi-Fi settings and a Retry. |
| Network, catalogue unreachable | *"Can't reach the app catalogue"* + Retry | **A distinct state, deliberately.** A captive portal, DNS failure or server outage all look like this while Wi-Fi is fine. |
| Network, catalogue fetched | The list | Rows resolve against what is installed. Normal operation. |
| Fetch in flight | Progress indicator | Bounded by a timeout. A screen that spins indefinitely is its own defect. |

> **Connected is not the same as reachable.** The most common way this screen goes wrong is
> treating "has Wi-Fi" as "can fetch". They need different copy because they need different fixes
> from the owner.

### Open questions for Design and Application

| Question | Why it matters |
|---|---|
| Offline with a cached catalogue: empty state, or the last known list? | Nothing installs offline either way, but the owner can still see what is on their car and at which version. Recommendation: show the cached list with a quiet offline notice; reserve the Wi-Fi empty state for a car that has never fetched a catalogue. |
| Wi-Fi specifically, or any connection? | The catalogue is a few KB; an APK is tens of MB. Likely answer: list on any connection, downloads on Wi-Fi only. |
| How long before we call it offline? | A head unit that just woke takes seconds to associate. Too short and a healthy car shows an error; too long and the screen feels broken. |

---

## 4. Interfaces and dependencies

Three teams build against each other. On a six-week clock there is no slack to absorb a contract
argument in week 4.

### Interface register

| Interface | Between | Freeze by | What must be agreed |
|---|---|---|---|
| **Catalogue API v1** | App ↔ Cloud | **S1 · day 5** | Endpoint, schema, versioning, error shapes, cache headers, forward compatibility for older vehicles |
| Artifact fetch | App ↔ Cloud | S1 · day 8 | Signed URL lifetime, range requests, integrity metadata, retry/backoff on a metered link |
| Vehicle identity | App ↔ Cloud ↔ Sec | S1 end | How a vehicle proves who it is: attestation source, credential storage, rotation, revocation |
| **Remote Config parameters** | App ↔ Cloud ↔ PMO | **S1 · day 5** | Parameter names, types, defaults baked into the APK, fetch policy, behaviour on failure or unknown values |
| Permission allowlist | App ↔ OTA | Track only | Privileged permissions and the allowlist XML shipped in the system image |
| Signing pipeline | OTA ↔ Sec | Track only | Platform key custody, who can sign, CI integration, audit trail |
| Rollout control | Cloud ↔ OTA ↔ PMO | S2 end | Bucketing rule, the identifier it hashes, per-entry rollout expression, the halt path |
| Telemetry schema | App ↔ Cloud ↔ Legal | S2 start | Install success/failure events and what is recorded. Device-scoped for the MVP |
| HMI spec | Design ↔ App | S2 start | Screens, states, copy, what is permitted on-screen while in motion |

### Staged rollout, and how it differs from A/B testing

These share plumbing and are routinely conflated. They answer different questions and fail in
different ways, so the plan treats them as two features.

| | Staged rollout | A/B testing |
|---|---|---|
| Question | Is this release safe to give everyone? | Which variant performs better? |
| Variants | One. Old versus new. | Two or more deliberate alternatives. |
| Ends when | 100% of the fleet — or halted | A winner reaches significance |
| Watches | Failure rate | A goal metric chosen in advance |
| Reacts by | Halting **immediately**, within minutes | Concluding, over days or weeks |
| Belongs to | Release safety — **in MVP** | Product optimisation — **post-MVP** |

> **Do not use an experiment framework as a safety gate.** Firebase A/B Testing runs to
> statistical significance against a goal metric. It is not built to notice a crash spike and stop
> within minutes. Wiring release safety through it means the mechanism that should halt a bad
> rollout is instead waiting for a result.

**Where the rollout percentage should live: the catalogue service, not Remote Config.** The
catalogue already authenticates the device and decides what it may see. Splitting the answer
across two systems means two consoles that must agree. It also survives the Google Play services
question — the catalogue is our own service.

Design points to settle in sprint 1:

- **Stable bucket.** Each vehicle hashes to a fixed bucket 0–99 from a stable identifier. It must
  not change between fetches, or a version could be withdrawn mid-download.
- **Server-side evaluation.** The catalogue returns only what this device may install; no client
  gate to get wrong or bypass.
- **Monotonic.** Percentages only go up during a rollout. Narrowing halts new devices; it does not
  claw back existing ones. Clawback is rollback.
- **Halt is one change.** 0% stops new installs immediately. This is the kill switch.
- **Per-app, not global.** Lucid Media at 10% while Lucid Radio is at 100% must be expressible.
- **Observable.** Someone must see which devices are on which version, or the percentage is blind.

### Rollback

Android compares **`versionCode`, never `versionName`**. Publishing the old build under a higher
*name* does nothing; the *code* is the ordering key.

| Mechanism | Needs | Keeps data | How it behaves |
|---|---|---|---|
| **Stop offering it** — *in MVP* | Nothing, server-side | n/a | Pull the version and halt the rollout. Protects every device that has not updated. Cheapest and fastest; reach for it first. |
| **Roll forward** — *S3 should* | Nothing special | Yes\* | Rebuild the last-good source with a higher `versionCode`. Works on any build. \*Data survives only if the bad version did not migrate it beyond what the old code can read. |
| **Downgrade install** — *post-MVP* | Privileged | Usually no | A normal app cannot: the platform refuses with `INSTALL_FAILED_VERSION_DOWNGRADE`. Generally wipes app data. |
| **Rollback framework** — *post-MVP* | Privileged | **Yes** | Android 10+ retains the previous APK and its data. Purpose-built. Rollbacks expire, consume storage, and the APIs are `@SystemApi` — OTA to confirm on our image. |

> **The store's own rollback is OTA's problem, by design.** A store that updated itself could not
> roll itself back — the broken build would be the component asked to recover. Shipping in the
> system image removes that circularity. Everything in the table above applies to the *managed
> apps*, not the store.

### Security controls

Four are inside the six weeks. Building without them means retrofitting security into a working
system, which is always dearer.

| Control | In MVP | Owner | Requirement |
|---|---|---|---|
| TLS with pinning | Yes | App · Sec | Catalogue and artifact traffic pinned, with a rotation plan that does not brick vehicles |
| Vehicle authentication | Yes | Cloud · Sec | The catalogue must not be an open endpoint serving APKs to the internet |
| APK signature verification | Yes | App · Sec | Verify the signing certificate matches the expected publisher **before** install. A SHA-256 proves the file arrived intact, not that it is legitimate |
| Rollback protection | Yes | App · Cloud | Reject downgrades so a stale signed artifact cannot be replayed |
| Least privilege | Yes | App · Sec | Written rationale on file for `INSTALL_PACKAGES` and `QUERY_ALL_PACKAGES` |
| Signing key custody | Post | OTA · Sec | Platform key in an HSM, access logged, signing only from CI |
| Kill switch | S3 should | Cloud · OTA | Server-side halt, plus a client-side minimum trusted version floor |
| Penetration test | Post | Sec | Independent test of catalogue, artifact fetch and install |

---

## 5. Three sprints, six weeks

Two build teams on fixed scope, with an unblock track alongside. The build track demos something
at the end of every sprint. The unblock track produces *answers*.

| Sprint | Weeks | Goal | Demo at the review |
|---|---|---|---|
| **S1** | 1–2 | **A real spine.** The app talks to a real catalogue service over TLS instead of a laptop harness. | App on a dev device lists apps served by the deployed service, resolves Install / Update / Installed correctly, and still renders from cache with the network off. Auto-update policy reads from Remote Config; flipping the parameter changes behaviour on next launch. |
| **S2** | 3–4 | **End to end, authenticated.** A release manager publishes; a device installs it. | Publish a new version; an authenticated device picks it up, verifies the APK signature, installs it, and the result appears in telemetry. An APK with a wrong signature is refused. |
| **S3** | 5–6 | **Survives being handled badly.** Plus a costed plan for production. | Failure matrix run live — no network, power loss mid-download, full storage, corrupt APK, wrong signature. Staged rollout at 10%, ramp to 100%, then halt a second rollout at 0%. Rollback drill, timed. Plus the production plan. |

A sprint that cannot demo its goal has not finished. The response is to cut Should-scope, never to
extend the sprint.

### The unblock track

Queue-time problems, not effort problems. None can be finished in six weeks by working harder; all
can be *started* in week 1. Each needs a written answer by the week 6 review.

| Owner | Question | What the answer changes |
|---|---|---|
| OTA | Can we get a platform-signed build, and by when? | Decides whether installs of managed apps are ever silent. The largest fork in the product experience. |
| Legal | May we distribute third-party APKs, and on what terms? | Decides whether the catalogue is first-party only. |
| Design | What may be shown and touched while the vehicle is moving? | Constrains the UI before it is built properly. |
| Security | What is the pen-test scope, and who runs it? | Sets the lead time between MVP and anything customer-facing. |
| OTA · PMO | What hardware do we get, and when? | Everything privileged needs a real head unit. |
| PMO · Cloud | Is there an existing Lucid backend to extend? | Building a second delivery service when one exists is the most expensive avoidable mistake here. |

---

## 6. Risks

Most severe risks sit *after* week 6, because the MVP scope was drawn to route around them. The one
that genuinely threatens the six-week date is the API contract.

| Risk | Severity | Owner | Mitigation |
|---|---|---|---|
| **Firebase needs Google Play services** | Verify first | App · OTA | Remote Config requires GMS. An AOSP head unit without Google Automotive Services cannot use it at all. Confirm which one Lucid ships **before sprint 1** — the answer decides whether this approach is viable. |
| **Nothing wakes the app** | High | App · PMO | Remote Config delivers configuration, not background execution. With WorkManager removed and nothing in its place, an app never opened never updates. Pick a wake-up source — §7. |
| **API contract slips past day 5** | High | PMO | The single highest risk to the date. Both build teams stall, or proceed on divergent assumptions and collide in sprint 2 with no slack. Freeze even if v1 is imperfect, and version it. |
| **Experiment framework used as a safety gate** | High | App · Cloud | A/B testing does not halt on a crash spike. Keep release safety on the catalogue path. |
| **Rollback assumed, not proven** | High | App · OTA | Every path has a catch. Rehearse one for real in sprint 3 — an untested rollback is not a rollback. |
| **Third-party distribution rights** | Blocker *(post-MVP)* | Legal | Not a blocker for six weeks — the MVP catalogue is first-party only. Becomes the blocker immediately after. File in week 1. |
| **Platform signing key access** | Blocker *(post-MVP)* | OTA | The MVP runs unprivileged. Blocks everything silent afterwards. Queue time is the cost, not the work. |
| **Store fixes wait for the OTA train** | Accepted | PMO · OTA | Deliberate. Catalogue content and policy reach vehicles without a store release, so the binary need not move often. Residual exposure is a defect in install logic — that is what the kill switch is for. |
| **Driver-distraction certification** | High | Design · Sec | Late discovery forces a UI rewrite. Constraints written down by end of sprint 2. |
| **Bench rig availability** | Medium | QA · OTA | MVP runs on dev devices. Everything after needs a real head unit — book it during sprint 1. |
| **Metered-connection cost** | Medium | Cloud · PMO | MVP assumes unmetered Wi-Fi. Agree the real policy before anything reaches a vehicle on a cellular plan. |

---

## 7. Decisions needed

The first three block week-1 work and need answers before kickoff. The rest can be answered by the
unblock track during the six weeks.

1. **Confirm the MVP runs unprivileged.** This is what makes six weeks credible. The store shows
   the system install dialog rather than installing silently, removing the platform-key dependency
   from the critical path. The prototype already picks its mode at runtime, so nothing is thrown
   away when the key arrives — but if silent install is needed *inside* the six weeks, the date
   moves.

2. **Confirm the MVP catalogue is first-party only.** Lucid Media and Lucid Radio, no third-party
   apps. That removes the legal blocker from the six weeks completely. If a third-party app must
   be in the MVP demo, Legal moves onto the critical path and the date is no longer ours to
   control.

3. **Who are the two build teams, and are they free for six weeks?** Application and Cloud carry
   the whole build. Part-time or shared engineers make a fixed-scope sprint plan fiction. This is
   the assumption most likely to be quietly wrong.

4. **What wakes the store when nobody opens it?** Removing the WorkManager job removes the only
   thing that ran on its own. Cheapest first: (a) accept on-launch only; (b) a boot-completed
   receiver, so the store checks once per ignition cycle — no scheduler, no Firebase dependency
   for waking, and a natural cadence for a car; (c) FCM push when the catalogue changes.
   **Recommendation: (b) for the MVP**, with (c) post-MVP.

5. **Does the rollout percentage live in the catalogue or in Remote Config?** Recommendation: the
   catalogue. One source of truth, one console, an immediate halt, and it keeps working without
   Google Play services.

6. **Which rollback mechanism are we committing to?** For the *managed apps* — the store's own
   recovery is OTA's. Recommendation: roll-forward for the MVP, the rollback framework post-MVP
   once the key lands, and the server-side halt always as the first response.

7. **Does auto-update default on or off?** A product and legal question. Changes the consent
   model, owner-facing terms and data-cost exposure. Needed before the post-MVP build.

8. **What is the pilot fleet after the MVP?** Employee vehicles, a test fleet, or selected
   customers. Determines the defect bar and how fast a bad build can be recovered.

---

*Six weeks delivers an internal MVP on developer hardware, not a customer release. The prototype
audit in §1 reflects code that exists and has been run; everything marked* Throwaway *or* Not
started *is new work. Dates hold only while the Must-scope in §1 holds.*

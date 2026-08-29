// Generates docs/LucidAppStore-Delivery-Plan.docx for sharing outside the repo.
//
//   npm install docx        (once, anywhere on the path)
//   node tools/build-delivery-doc.js docs/LucidAppStore-Delivery-Plan.docx
//
// docs/DELIVERY_PLAN.md is the source of truth. Change the Markdown first, then mirror the
// change here and regenerate - the .docx is a build artifact, not a document to hand-edit.
const fs = require('fs');
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType,
  Table, TableRow, TableCell, WidthType, ShadingType, BorderStyle,
  PageOrientation, LevelFormat, convertInchesToTwip,
} = require('docx');

// US Letter, 0.8" margins -> content width in DXA
const PAGE_W = 12240, MARGIN = 1152;
const CONTENT = PAGE_W - MARGIN * 2; // 9936

const INK = '15202B';
const SOFT = '44586A';
const DEEP = '0B3D5C';
const RULE = 'C8D8E0';
const HEADFILL = 'E8F0F4';
const ZEBRA = 'F5F9FA';

const NO_BORDER = { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' };
const HAIR = { style: BorderStyle.SINGLE, size: 3, color: RULE };

function txt(text, opts = {}) {
  return new TextRun({ text, font: 'Calibri', size: opts.size || 20, bold: !!opts.bold,
    italics: !!opts.italics, color: opts.color || INK });
}

function para(text, opts = {}) {
  return new Paragraph({
    alignment: opts.align,
    spacing: { before: opts.before ?? 0, after: opts.after ?? 120, line: 264 },
    indent: opts.indent,
    border: opts.border,
    children: Array.isArray(text) ? text : [txt(text, opts)],
  });
}

function h1(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_1,
    spacing: { before: 360, after: 140 },
    border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: DEEP } },
    children: [new TextRun({ text, font: 'Calibri', size: 30, bold: true, color: DEEP })],
  });
}

function h2(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_2,
    spacing: { before: 260, after: 100 },
    children: [new TextRun({ text, font: 'Calibri', size: 24, bold: true, color: INK })],
  });
}

function note(text) {
  return new Paragraph({
    spacing: { before: 120, after: 160, line: 264 },
    indent: { left: 220 },
    border: { left: { style: BorderStyle.SINGLE, size: 18, color: DEEP, space: 10 } },
    children: [new TextRun({ text, font: 'Calibri', size: 19, italics: true, color: SOFT })],
  });
}

function bullets(items) {
  return items.map((t) => new Paragraph({
    numbering: { reference: 'dots', level: 0 },
    spacing: { after: 70, line: 264 },
    children: [txt(t, { size: 19 })],
  }));
}

// rows: array of arrays of strings. First row = header. widths: fractions summing to 1.
function table(header, rows, fractions) {
  const widths = fractions.map((f) => Math.round(CONTENT * f));
  widths[widths.length - 1] = CONTENT - widths.slice(0, -1).reduce((a, b) => a + b, 0);

  const cell = (text, i, isHeader, zebra) => new TableCell({
    width: { size: widths[i], type: WidthType.DXA },
    shading: { type: ShadingType.CLEAR, fill: isHeader ? HEADFILL : (zebra ? ZEBRA : 'FFFFFF'), color: 'auto' },
    margins: { top: 70, bottom: 70, left: 110, right: 110 },
    borders: { top: HAIR, bottom: HAIR, left: NO_BORDER, right: NO_BORDER },
    children: [new Paragraph({
      spacing: { after: 0, line: 240 },
      children: [txt(text, { size: 17, bold: isHeader, color: isHeader ? DEEP : INK })],
    })],
  });

  return new Table({
    width: { size: CONTENT, type: WidthType.DXA },
    columnWidths: widths,
    rows: [
      new TableRow({ tableHeader: true, children: header.map((t, i) => cell(t, i, true, false)) }),
      ...rows.map((r, ri) => new TableRow({ children: r.map((t, i) => cell(t, i, false, ri % 2 === 1)) })),
    ],
  });
}

const spacer = () => new Paragraph({ spacing: { after: 160 }, children: [] });

// ---------------------------------------------------------------- content

const body = [];

// Title block
body.push(new Paragraph({
  spacing: { after: 60 },
  children: [new TextRun({ text: 'LUCID · INTERNAL DRAFT', font: 'Calibri', size: 16, bold: true, color: DEEP, characterSpacing: 40 })],
}));
body.push(new Paragraph({
  spacing: { after: 80 },
  children: [new TextRun({ text: 'LucidAppStore Delivery Plan', font: 'Calibri', size: 52, bold: true, color: INK })],
}));
body.push(new Paragraph({
  spacing: { after: 200 },
  border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: DEEP } },
  children: [new TextRun({ text: 'Three sprints · Six weeks · Minimum viable on-vehicle app store', font: 'Calibri', size: 22, color: SOFT })],
}));
body.push(para('A minimum viable on-vehicle app store, built in three two-week sprints, while Design, Legal, Security and OTA work the long-lead blockers in parallel.', { size: 21, color: SOFT, after: 240 }));

// Executive summary
body.push(h1('Executive summary'));
body.push(para([txt('The ask. ', { bold: true }), txt('Staff two build teams (Application, Cloud) for six weeks, plus part-time capacity from Design, OTA, Security, QA and Legal to run an unblock track alongside them.')]));
body.push(para([txt('What we get at week 6. ', { bold: true }), txt('Two deliverables, not one:')], { after: 80 }));
body.push(...bullets([
  'A working MVP on developer hardware — a real catalogue service over authenticated TLS, apps installing and updating on real devices, staged rollout and a kill switch.',
  'A production plan with real dates, informed by answers the unblock track brings back. This is the more valuable of the two, and it only exists if that track is genuinely staffed.',
]));
body.push(para([txt('What we do not get at week 6. ', { bold: true }), txt('Anything gated on an external dependency with queue time: platform signing key access, third-party distribution rights, an independent penetration test, driver-distraction certification, customer vehicles. These are not compressible by working harder.')], { before: 100 }));
body.push(para([txt('Why six weeks is credible. ', { bold: true }), txt('Three scope decisions route the plan around the slowest dependencies:')], { after: 100 }));
body.push(table(['Decision', 'Effect'], [
  ['The MVP runs unprivileged', 'Removes the platform signing key from the critical path'],
  ['The catalogue is first-party only', 'Removes the distribution-rights blocker entirely'],
  ['A hard Must / Should / Won’t list', 'Makes the date mean something'],
], [0.38, 0.62]));
body.push(spacer());
body.push(para([txt('The three things most likely to move the date.', { bold: true })], { after: 80 }));
body.push(...bullets([
  'The catalogue API contract is not frozen by day 5.',
  'Application or Cloud engineers turn out to be part-time or shared.',
  'Firebase Remote Config proves unavailable on our head unit (see §6).',
]));
body.push(para([txt('Decisions needed before kickoff. ', { bold: true }), txt('Three, in §7: confirm the unprivileged MVP, confirm the first-party-only catalogue, and confirm the two build teams are dedicated.')], { before: 100 }));

// 1
body.push(h1('1 · Where we are today'));
body.push(para('A working single-developer prototype exists and runs end-to-end on an emulator. It is a credible foundation and an unshippable product. Six weeks is enough to turn it into a real internal MVP — provided we are precise about which parts already work and ruthless about what we are not attempting yet.'));
body.push(h2('Prototype audit'));
body.push(table(['Capability', 'State', 'Notes'], [
  ['Local version detection', 'Proven', 'Reads versionCode via PackageManager, targeted per catalogue entry. Needs QUERY_ALL_PACKAGES because the catalogue is server-driven.'],
  ['Install / update engine', 'Proven', 'PackageInstaller sessions, dual-mode: silent when privileged, system dialog otherwise. Verified on emulator both ways.'],
  ['Auto-update flag', 'Rework', 'On-launch pass is proven. The scheduled job is replaced by Firebase Remote Config as the policy source; the WorkManager scheduler, worker and boot receiver come out.'],
  ['Remote Config integration', 'Not started', 'New. Needs a Firebase project, a parameter schema, a fetch policy, and a decision on what wakes the app when it is closed.'],
  ['Catalogue contract', 'Draft', 'Schema exists and works, but has never been reviewed by a backend owner. Freeze this first.'],
  ['Store update path', 'Decided', 'The store APK ships in the system image and is updated by OTA. It does not update itself.'],
  ['Catalogue server', 'Throwaway', 'Local Node harness for developer testing. No auth, no TLS, no CDN, no fleet targeting.'],
  ['Security posture', 'Not started', 'SHA-256 payload check only. No transport auth, no APK signature verification, no rollback protection, no threat model.'],
  ['Rollback', 'Not started', 'Nothing in the prototype can undo a bad install.'],
  ['Staged rollout', 'In scope', 'Today every vehicle that polls gets every update. Percentage rollout is Sprint 3 scope, served from the catalogue.'],
  ['UX / HMI', 'Placeholder', 'Functional layout, never reviewed against driver-distraction rules or Lucid’s design system.'],
], [0.22, 0.13, 0.65]));
body.push(spacer());

body.push(h2('Three delivery tiers'));
body.push(para('Each kind of change has its own path and cadence. Keeping them separate is what stops the store’s release train becoming a bottleneck for everything else.'));
body.push(table(['What changes', 'Delivered by', 'Cadence', 'Examples'], [
  ['Catalogue content', 'Catalogue service', 'Any time', 'Which apps exist, versions, titles, icons'],
  ['Store policy', 'Firebase Remote Config', 'Any time', 'Auto-update on/off, kill switch, cohorts'],
  ['Store binary', 'OTA', 'OTA train', 'The store APK itself — UI, install logic, fixes'],
], [0.22, 0.24, 0.16, 0.38]));
body.push(spacer());
body.push(para('The store’s own release cadence is tied to the OTA train. That is affordable precisely because the two tiers that change often need no new store APK.'));

body.push(h2('Minimum scope for the six weeks'));
body.push(para('Anything not on the Must list is out unless a sprint review says otherwise.'));
body.push(table(['Priority', 'Scope'], [
  ['Must', 'Catalogue API v1, frozen and implemented · catalogue service on real hosting with TLS · per-device authentication · APK artifact hosting with integrity metadata · app fetches, resolves state, installs and updates · auto-update driven by Remote Config, checked on launch · APK signature verification before install · catalogue cached to disk so the car works offline · a publishing path a release manager can run without editing JSON · install success/failure telemetry · automated tests on dev devices'],
  ['Should', 'Staged rollout by percentage against a stable per-device bucket · server-side kill switch · roll-forward rollback, exercised once as a drill · cohort allowlist · real-time Remote Config listener'],
  ['Won’t', 'Privileged silent install · platform rollback framework · downgrade installs · FCM push wake-up · customer or VIN-targeted vehicles · Firebase A/B Testing experiments · independent penetration test · third-party apps · driver-distraction certification · production on-call'],
], [0.13, 0.87]));

// 2
body.push(h1('2 · Teams and ownership'));
body.push(para('Application and Cloud are the build teams. The rest run the unblock track — clearing the dependencies that decide what happens after week 6.'));
body.push(table(['Team', 'Owns', 'Responsibilities'], [
  ['Program Management', 'Schedule, gates, dependency register, escalation', 'Critical path; release train; the day-5 API contract freeze; tracking the legal blocker to closure'],
  ['Product & UX Design', 'HMI spec, flows, copy, distraction compliance', 'Store flows and the auto-update consent model; what may be shown and touched in motion; applying Lucid’s design system'],
  ['Android Application', 'The APK, its tests, its performance budget', 'Catalogue fetch, version resolution, download, install; Remote Config as policy source with safe baked-in defaults; offline behaviour; rollback of managed apps'],
  ['Cloud & Catalogue', 'API, artifact hosting, publishing tooling, uptime', 'Authenticated catalogue service; artifact store and CDN; publishing console; staged rollout buckets and one-change halt; rollout observability'],
  ['OTA & Release Eng', 'System image integration, signing, rollback', 'Delivery of the store APK in the system image; platform key custody and signing pipeline; recovery when a store build is bad'],
  ['Security & Compliance', 'Threat model, sign-off gates, pen test', 'Threat model for a component that can install software on a vehicle; reviews transport, auth and artifact verification; regulatory position'],
  ['QA & Vehicle Validation', 'Test strategy, bench rig, quality bar', 'Unit through in-vehicle; the failure matrix; device automation; defect triage'],
  ['Legal & Partnerships (advisory)', 'The distribution-rights blocker', 'Distribution agreements; owner-facing terms for automatic installation; data-protection review of telemetry'],
], [0.2, 0.26, 0.54]));
body.push(spacer());

body.push(h2('How they work together'));
body.push(table(['Forum', 'Frequency', 'Attendees', 'Purpose'], [
  ['Contract sync', 'Daily, week 1 only', 'App · Cloud · PMO', 'Gets the API frozen by day 5. Stops the moment it is signed off.'],
  ['Build stand-up', 'Daily, 15 min', 'App · Cloud · QA', 'Blockers only. One stand-up for both build teams so integration problems surface same-day.'],
  ['Unblock review', 'Weekly', 'PMO · Design · OTA · Sec · Legal', 'Progress on the six questions in §5. Chased, not discussed.'],
  ['Sprint review', 'End of each sprint', 'All leads + sponsor', 'Demo against the written sprint goal. Scope changes decided here and nowhere else.'],
], [0.17, 0.17, 0.24, 0.42]));

// 3
body.push(h1('3 · Opening the store'));
body.push(para('The store is reached from vehicle Settings, not a launcher icon. What the owner sees depends on whether the catalogue could be fetched — and there are three outcomes to design for, not two.'));
body.push(table(['Condition', 'Screen', 'Behaviour'], [
  ['No network', '“Connect to Wi-Fi to see available apps”', 'No fetch attempted. Offer a shortcut into Wi-Fi settings and a Retry.'],
  ['Network, catalogue unreachable', '“Can’t reach the app catalogue” + Retry', 'A distinct state, deliberately. A captive portal, DNS failure or server outage all look like this while Wi-Fi is fine.'],
  ['Network, catalogue fetched', 'The list', 'Rows resolve against what is installed. Normal operation.'],
  ['Fetch in flight', 'Progress indicator', 'Bounded by a timeout. A screen that spins indefinitely is its own defect.'],
], [0.24, 0.28, 0.48]));
body.push(spacer());
body.push(note('Connected is not the same as reachable. The most common way this screen goes wrong is treating “has Wi-Fi” as “can fetch”. They need different copy because they need different fixes from the owner.'));
body.push(h2('Open questions for Design and Application'));
body.push(table(['Question', 'Why it matters'], [
  ['Offline with a cached catalogue: empty state, or the last known list?', 'Nothing installs offline either way, but the owner can still see what is on their car and at which version. Recommendation: show the cached list with a quiet offline notice; reserve the Wi-Fi empty state for a car that has never fetched a catalogue.'],
  ['Wi-Fi specifically, or any connection?', 'The catalogue is a few KB; an APK is tens of MB. Likely answer: list on any connection, downloads on Wi-Fi only.'],
  ['How long before we call it offline?', 'A head unit that just woke takes seconds to associate. Too short and a healthy car shows an error; too long and the screen feels broken.'],
], [0.32, 0.68]));

// 4
body.push(h1('4 · Interfaces and dependencies'));
body.push(para('Three teams build against each other. On a six-week clock there is no slack to absorb a contract argument in week 4.'));
body.push(h2('Interface register'));
body.push(table(['Interface', 'Between', 'Freeze by', 'What must be agreed'], [
  ['Catalogue API v1', 'App ↔ Cloud', 'S1 · day 5', 'Endpoint, schema, versioning, error shapes, cache headers, forward compatibility for older vehicles'],
  ['Artifact fetch', 'App ↔ Cloud', 'S1 · day 8', 'Signed URL lifetime, range requests, integrity metadata, retry/backoff on a metered link'],
  ['Vehicle identity', 'App ↔ Cloud ↔ Sec', 'S1 end', 'How a vehicle proves who it is: attestation source, credential storage, rotation, revocation'],
  ['Remote Config parameters', 'App ↔ Cloud ↔ PMO', 'S1 · day 5', 'Parameter names, types, defaults baked into the APK, fetch policy, behaviour on failure or unknown values'],
  ['Permission allowlist', 'App ↔ OTA', 'Track only', 'Privileged permissions and the allowlist XML shipped in the system image'],
  ['Signing pipeline', 'OTA ↔ Sec', 'Track only', 'Platform key custody, who can sign, CI integration, audit trail'],
  ['Rollout control', 'Cloud ↔ OTA ↔ PMO', 'S2 end', 'Bucketing rule, the identifier it hashes, per-entry rollout expression, the halt path'],
  ['Telemetry schema', 'App ↔ Cloud ↔ Legal', 'S2 start', 'Install success/failure events and what is recorded. Device-scoped for the MVP'],
  ['HMI spec', 'Design ↔ App', 'S2 start', 'Screens, states, copy, what is permitted on-screen while in motion'],
], [0.2, 0.19, 0.13, 0.48]));
body.push(spacer());

body.push(h2('Staged rollout, and how it differs from A/B testing'));
body.push(para('These share plumbing and are routinely conflated. They answer different questions and fail in different ways, so the plan treats them as two features.'));
body.push(table(['', 'Staged rollout', 'A/B testing'], [
  ['Question', 'Is this release safe to give everyone?', 'Which variant performs better?'],
  ['Variants', 'One. Old versus new.', 'Two or more deliberate alternatives.'],
  ['Ends when', '100% of the fleet — or halted', 'A winner reaches significance'],
  ['Watches', 'Failure rate', 'A goal metric chosen in advance'],
  ['Reacts by', 'Halting immediately, within minutes', 'Concluding, over days or weeks'],
  ['Belongs to', 'Release safety — in MVP', 'Product optimisation — post-MVP'],
], [0.17, 0.44, 0.39]));
body.push(spacer());
body.push(note('Do not use an experiment framework as a safety gate. Firebase A/B Testing runs to statistical significance against a goal metric. It is not built to notice a crash spike and stop within minutes. Wiring release safety through it means the mechanism that should halt a bad rollout is instead waiting for a result.'));
body.push(para([txt('Where the rollout percentage should live: the catalogue service, not Remote Config. ', { bold: true }), txt('The catalogue already authenticates the device and decides what it may see. Splitting the answer across two systems means two consoles that must agree. It also survives the Google Play services question — the catalogue is our own service.')]));
body.push(para('Design points to settle in sprint 1:', { after: 80 }));
body.push(...bullets([
  'Stable bucket. Each vehicle hashes to a fixed bucket 0–99 from a stable identifier. It must not change between fetches, or a version could be withdrawn mid-download.',
  'Server-side evaluation. The catalogue returns only what this device may install; no client gate to get wrong or bypass.',
  'Monotonic. Percentages only go up during a rollout. Narrowing halts new devices; it does not claw back existing ones. Clawback is rollback.',
  'Halt is one change. 0% stops new installs immediately. This is the kill switch.',
  'Per-app, not global. Lucid Media at 10% while Lucid Radio is at 100% must be expressible.',
  'Observable. Someone must see which devices are on which version, or the percentage is blind.',
]));

body.push(h2('Rollback'));
body.push(para([txt('Android compares ', {}), txt('versionCode, never versionName', { bold: true }), txt('. Publishing the old build under a higher name does nothing; the code is the ordering key.')]));
body.push(table(['Mechanism', 'Needs', 'Keeps data', 'How it behaves'], [
  ['Stop offering it — in MVP', 'Nothing, server-side', 'n/a', 'Pull the version and halt the rollout. Protects every device that has not updated. Cheapest and fastest; reach for it first.'],
  ['Roll forward — S3 should', 'Nothing special', 'Yes*', 'Rebuild the last-good source with a higher versionCode. Works on any build. *Data survives only if the bad version did not migrate it beyond what the old code can read.'],
  ['Downgrade install — post-MVP', 'Privileged', 'Usually no', 'A normal app cannot: the platform refuses with INSTALL_FAILED_VERSION_DOWNGRADE. Generally wipes app data.'],
  ['Rollback framework — post-MVP', 'Privileged', 'Yes', 'Android 10+ retains the previous APK and its data. Purpose-built. Rollbacks expire, consume storage, and the APIs are @SystemApi — OTA to confirm on our image.'],
], [0.22, 0.15, 0.12, 0.51]));
body.push(spacer());
body.push(note('The store’s own rollback is OTA’s problem, by design. A store that updated itself could not roll itself back — the broken build would be the component asked to recover. Shipping in the system image removes that circularity. Everything above applies to the managed apps, not the store.'));

body.push(h2('Security controls'));
body.push(para('Four are inside the six weeks. Building without them means retrofitting security into a working system, which is always dearer.'));
body.push(table(['Control', 'In MVP', 'Owner', 'Requirement'], [
  ['TLS with pinning', 'Yes', 'App · Sec', 'Catalogue and artifact traffic pinned, with a rotation plan that does not brick vehicles'],
  ['Vehicle authentication', 'Yes', 'Cloud · Sec', 'The catalogue must not be an open endpoint serving APKs to the internet'],
  ['APK signature verification', 'Yes', 'App · Sec', 'Verify the signing certificate matches the expected publisher before install. A SHA-256 proves the file arrived intact, not that it is legitimate'],
  ['Rollback protection', 'Yes', 'App · Cloud', 'Reject downgrades so a stale signed artifact cannot be replayed'],
  ['Least privilege', 'Yes', 'App · Sec', 'Written rationale on file for INSTALL_PACKAGES and QUERY_ALL_PACKAGES'],
  ['Signing key custody', 'Post', 'OTA · Sec', 'Platform key in an HSM, access logged, signing only from CI'],
  ['Kill switch', 'S3 should', 'Cloud · OTA', 'Server-side halt, plus a client-side minimum trusted version floor'],
  ['Penetration test', 'Post', 'Sec', 'Independent test of catalogue, artifact fetch and install'],
], [0.23, 0.11, 0.14, 0.52]));

// 5
body.push(h1('5 · Three sprints, six weeks'));
body.push(para('Two build teams on fixed scope, with an unblock track alongside. The build track demos something at the end of every sprint. The unblock track produces answers.'));
body.push(table(['Sprint', 'Goal', 'Demo at the review'], [
  ['S1 · weeks 1–2', 'A real spine. The app talks to a real catalogue service over TLS instead of a laptop harness.', 'App on a dev device lists apps served by the deployed service, resolves Install / Update / Installed correctly, and still renders from cache with the network off. Auto-update policy reads from Remote Config; flipping the parameter changes behaviour on next launch.'],
  ['S2 · weeks 3–4', 'End to end, authenticated. A release manager publishes; a device installs it.', 'Publish a new version; an authenticated device picks it up, verifies the APK signature, installs it, and the result appears in telemetry. An APK with a wrong signature is refused.'],
  ['S3 · weeks 5–6', 'Survives being handled badly. Plus a costed plan for production.', 'Failure matrix run live — no network, power loss mid-download, full storage, corrupt APK, wrong signature. Staged rollout at 10%, ramp to 100%, then halt a second rollout at 0%. Rollback drill, timed. Plus the production plan.'],
], [0.16, 0.32, 0.52]));
body.push(spacer());
body.push(para('A sprint that cannot demo its goal has not finished. The response is to cut Should-scope, never to extend the sprint.'));

body.push(h2('The unblock track'));
body.push(para('Queue-time problems, not effort problems. None can be finished in six weeks by working harder; all can be started in week 1. Each needs a written answer by the week 6 review.'));
body.push(table(['Owner', 'Question', 'What the answer changes'], [
  ['OTA', 'Can we get a platform-signed build, and by when?', 'Decides whether installs of managed apps are ever silent. The largest fork in the product experience.'],
  ['Legal', 'May we distribute third-party APKs, and on what terms?', 'Decides whether the catalogue is first-party only.'],
  ['Design', 'What may be shown and touched while the vehicle is moving?', 'Constrains the UI before it is built properly.'],
  ['Security', 'What is the pen-test scope, and who runs it?', 'Sets the lead time between MVP and anything customer-facing.'],
  ['OTA · PMO', 'What hardware do we get, and when?', 'Everything privileged needs a real head unit.'],
  ['PMO · Cloud', 'Is there an existing Lucid backend to extend?', 'Building a second delivery service when one exists is the most expensive avoidable mistake here.'],
], [0.13, 0.37, 0.5]));

// 6
body.push(h1('6 · Risks'));
body.push(para('Most severe risks sit after week 6, because the MVP scope was drawn to route around them. The one that genuinely threatens the six-week date is the API contract.'));
body.push(table(['Risk', 'Severity', 'Owner', 'Mitigation'], [
  ['Firebase needs Google Play services', 'Verify first', 'App · OTA', 'Remote Config requires GMS. An AOSP head unit without Google Automotive Services cannot use it at all. Confirm which one Lucid ships before sprint 1 — the answer decides whether this approach is viable.'],
  ['Nothing wakes the app', 'High', 'App · PMO', 'Remote Config delivers configuration, not background execution. With WorkManager removed and nothing in its place, an app never opened never updates. Pick a wake-up source — §7.'],
  ['API contract slips past day 5', 'High', 'PMO', 'The single highest risk to the date. Both build teams stall, or proceed on divergent assumptions and collide in sprint 2 with no slack. Freeze even if v1 is imperfect, and version it.'],
  ['Experiment framework used as a safety gate', 'High', 'App · Cloud', 'A/B testing does not halt on a crash spike. Keep release safety on the catalogue path.'],
  ['Rollback assumed, not proven', 'High', 'App · OTA', 'Every path has a catch. Rehearse one for real in sprint 3 — an untested rollback is not a rollback.'],
  ['Third-party distribution rights', 'Blocker (post-MVP)', 'Legal', 'Not a blocker for six weeks — the MVP catalogue is first-party only. Becomes the blocker immediately after. File in week 1.'],
  ['Platform signing key access', 'Blocker (post-MVP)', 'OTA', 'The MVP runs unprivileged. Blocks everything silent afterwards. Queue time is the cost, not the work.'],
  ['Store fixes wait for the OTA train', 'Accepted', 'PMO · OTA', 'Deliberate. Catalogue content and policy reach vehicles without a store release. Residual exposure is a defect in install logic — that is what the kill switch is for.'],
  ['Driver-distraction certification', 'High', 'Design · Sec', 'Late discovery forces a UI rewrite. Constraints written down by end of sprint 2.'],
  ['Bench rig availability', 'Medium', 'QA · OTA', 'MVP runs on dev devices. Everything after needs a real head unit — book it during sprint 1.'],
  ['Metered-connection cost', 'Medium', 'Cloud · PMO', 'MVP assumes unmetered Wi-Fi. Agree the real policy before anything reaches a vehicle on a cellular plan.'],
], [0.24, 0.14, 0.12, 0.5]));

// 7
body.push(h1('7 · Decisions needed'));
body.push(para('The first three block week-1 work and need answers before kickoff. The rest can be answered by the unblock track during the six weeks.'));
const decisions = [
  ['Confirm the MVP runs unprivileged.', 'This is what makes six weeks credible. The store shows the system install dialog rather than installing silently, removing the platform-key dependency from the critical path. The prototype already picks its mode at runtime, so nothing is thrown away when the key arrives — but if silent install is needed inside the six weeks, the date moves.'],
  ['Confirm the MVP catalogue is first-party only.', 'Lucid Media and Lucid Radio, no third-party apps. That removes the legal blocker from the six weeks completely. If a third-party app must be in the MVP demo, Legal moves onto the critical path and the date is no longer ours to control.'],
  ['Who are the two build teams, and are they free for six weeks?', 'Application and Cloud carry the whole build. Part-time or shared engineers make a fixed-scope sprint plan fiction. This is the assumption most likely to be quietly wrong.'],
  ['What wakes the store when nobody opens it?', 'Cheapest first: (a) accept on-launch only; (b) a boot-completed receiver, so the store checks once per ignition cycle — no scheduler, no Firebase dependency for waking, and a natural cadence for a car; (c) FCM push when the catalogue changes. Recommendation: (b) for the MVP, with (c) post-MVP.'],
  ['Does the rollout percentage live in the catalogue or in Remote Config?', 'Recommendation: the catalogue. One source of truth, one console, an immediate halt, and it keeps working without Google Play services.'],
  ['Which rollback mechanism are we committing to?', 'For the managed apps — the store’s own recovery is OTA’s. Recommendation: roll-forward for the MVP, the rollback framework post-MVP once the key lands, and the server-side halt always as the first response.'],
  ['Does auto-update default on or off?', 'A product and legal question. Changes the consent model, owner-facing terms and data-cost exposure. Needed before the post-MVP build.'],
  ['What is the pilot fleet after the MVP?', 'Employee vehicles, a test fleet, or selected customers. Determines the defect bar and how fast a bad build can be recovered.'],
];
decisions.forEach(([q, a], i) => {
  body.push(new Paragraph({
    spacing: { before: 140, after: 40, line: 264 },
    children: [txt(`Q${i + 1}  `, { bold: true, color: DEEP, size: 19 }), txt(q, { bold: true })],
  }));
  body.push(new Paragraph({
    spacing: { after: 60, line: 264 },
    indent: { left: 340 },
    children: [txt(a, { size: 19, color: SOFT })],
  }));
});

body.push(new Paragraph({
  spacing: { before: 360, after: 0, line: 264 },
  border: { top: { style: BorderStyle.SINGLE, size: 6, color: DEEP } },
  children: [],
}));
body.push(para('Six weeks delivers an internal MVP on developer hardware, not a customer release. The prototype audit in §1 reflects code that exists and has been run; everything marked Throwaway or Not started is new work. Dates hold only while the Must-scope in §1 holds.', { size: 17, color: SOFT, italics: true, before: 100 }));

// ---------------------------------------------------------------- document

const doc = new Document({
  numbering: {
    config: [{
      reference: 'dots',
      levels: [{
        level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 340, hanging: 200 } } },
      }],
    }],
  },
  styles: {
    default: {
      document: { run: { font: 'Calibri', size: 20, color: INK } },
    },
  },
  sections: [{
    properties: {
      page: {
        size: { width: PAGE_W, height: 15840, orientation: PageOrientation.PORTRAIT },
        margin: { top: MARGIN, bottom: MARGIN, left: MARGIN, right: MARGIN },
      },
    },
    children: body,
  }],
});

Packer.toBuffer(doc).then((buf) => {
  fs.writeFileSync(process.argv[2], buf);
  console.log('wrote', process.argv[2], (buf.length / 1024).toFixed(0) + ' KB');
});

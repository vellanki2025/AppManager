#!/usr/bin/env node
/**
 * LucidAppStore local catalogue server.
 *
 * Serves the app catalogue and the APK/icon files so the Android app can be exercised end to end
 * over Wi-Fi without any cloud infrastructure.
 *
 *   GET  /health                 -> { ok: true }
 *   GET  /api/v1/apps            -> the catalogue (FR-2.2 schema)
 *   GET  /apks/<file>.apk        -> an APK, with Content-Length and range support
 *   GET  /icons/<file>.png       -> an icon
 *   POST /admin/bump/:pkg        -> bump a versionCode so "Installed" flips to "Update"
 *   POST /admin/set/:pkg         -> set versionCode/versionName explicitly
 *   POST /admin/reload           -> re-read data/apps.json from disk
 *
 * URLs in the catalogue response are rewritten to the host the request arrived on, so a phone
 * hitting http://192.168.1.20:8080 gets APK URLs it can actually reach.
 */

const express = require('express');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_FILE = path.join(__dirname, 'data', 'apps.json');
const APK_DIR = path.join(__dirname, 'apks');
const ICON_DIR = path.join(__dirname, 'icons');

let catalog = loadCatalog();

function loadCatalog() {
  const raw = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
  return raw;
}

/** SHA-256 + size of an APK on disk, so the client can verify what it downloaded. */
function apkFacts(fileName) {
  if (!fileName) return { sizeBytes: 0, sha256: null, exists: false };
  const full = path.join(APK_DIR, fileName);
  if (!fs.existsSync(full)) return { sizeBytes: 0, sha256: null, exists: false };
  const buf = fs.readFileSync(full);
  return {
    sizeBytes: buf.length,
    sha256: crypto.createHash('sha256').update(buf).digest('hex'),
    exists: true,
  };
}

function baseUrlFor(req) {
  const proto = req.headers['x-forwarded-proto'] || req.protocol || 'http';
  return `${proto}://${req.headers.host}`;
}

function toWireApp(app, base) {
  const facts = apkFacts(app.apkFile);
  return {
    packageName: app.packageName,
    title: app.title,
    versionName: app.versionName,
    versionCode: app.versionCode,
    apkUrl: `${base}/apks/${app.apkFile}`,
    iconUrl: app.iconFile ? `${base}/icons/${app.iconFile}` : null,
    sizeBytes: facts.sizeBytes,
    // Only advertise a hash when the file is really there; the client refuses a mismatch.
    sha256: facts.sha256,
    releaseNotes: app.releaseNotes || null,
  };
}

const app = express();
app.use(express.json());

app.use((req, _res, next) => {
  console.log(`${new Date().toISOString()}  ${req.method} ${req.originalUrl}  <- ${req.ip}`);
  next();
});

app.get('/health', (_req, res) => res.json({ ok: true, apps: catalog.apps.length }));

app.get('/api/v1/apps', (req, res) => {
  const base = baseUrlFor(req);
  res.json({
    manifestVersion: catalog.manifestVersion || 1,
    generatedAt: new Date().toISOString(),
    apps: catalog.apps.map((a) => toWireApp(a, base)),
  });
});

app.use('/apks', express.static(APK_DIR, {
  setHeaders: (res) => res.setHeader('Content-Type', 'application/vnd.android.package-archive'),
  acceptRanges: true,
  fallthrough: false,
}));

app.use('/icons', express.static(ICON_DIR, { fallthrough: false }));

// ---- test helpers -----------------------------------------------------------

app.post('/admin/bump/:pkg', (req, res) => {
  const target = catalog.apps.find((a) => a.packageName === req.params.pkg);
  if (!target) return res.status(404).json({ error: `unknown package ${req.params.pkg}` });
  target.versionCode += 1;
  const parts = String(target.versionName).split('.');
  parts[parts.length - 1] = String(Number(parts[parts.length - 1] || 0) + 1);
  target.versionName = parts.join('.');
  console.log(`  bumped ${target.packageName} -> ${target.versionName} (${target.versionCode})`);
  res.json({
    ...target,
    warning:
      'The catalogue now claims a version the APK on disk does not actually declare. This is ' +
      'fine for exercising the Install/Update/Installed UI states, but an install will not ' +
      'satisfy it and the row will stay on "Update". Use tools/publish-samples.sh --bump N to ' +
      'publish a genuinely newer APK.',
  });
});

app.post('/admin/set/:pkg', (req, res) => {
  const target = catalog.apps.find((a) => a.packageName === req.params.pkg);
  if (!target) return res.status(404).json({ error: `unknown package ${req.params.pkg}` });
  if (req.body.versionCode != null) target.versionCode = Number(req.body.versionCode);
  if (req.body.versionName != null) target.versionName = String(req.body.versionName);
  res.json(target);
});

app.post('/admin/reload', (_req, res) => {
  catalog = loadCatalog();
  res.json({ reloaded: true, apps: catalog.apps.length });
});

app.use((req, res) => res.status(404).json({ error: 'not found', path: req.originalUrl }));

// ---- boot -------------------------------------------------------------------

function lanAddresses() {
  return Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => i.address);
}

if (require.main === module) {
  for (const dir of [APK_DIR, ICON_DIR]) fs.mkdirSync(dir, { recursive: true });

  app.listen(PORT, HOST, () => {
    console.log('');
    console.log('  LucidAppStore catalogue server');
    console.log('  ------------------------------');
    console.log(`  local        http://localhost:${PORT}`);
    console.log(`  emulator     http://10.0.2.2:${PORT}      <- use this in an AVD`);
    for (const ip of lanAddresses()) {
      console.log(`  wifi         http://${ip}:${PORT}        <- use this on a real device`);
    }
    console.log('');
    for (const a of catalog.apps) {
      const f = apkFacts(a.apkFile);
      const state = f.exists ? `${(f.sizeBytes / 1024).toFixed(0)} KB` : 'APK MISSING (put one in server/apks/)';
      console.log(`  ${a.packageName.padEnd(24)} ${a.versionName.padEnd(10)} code=${String(a.versionCode).padEnd(10)} ${state}`);
    }
    console.log('');
  });
}

module.exports = { app, toWireApp, apkFacts };

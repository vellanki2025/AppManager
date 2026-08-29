#!/usr/bin/env bash
#
# Builds the three sample fixture apps and publishes them to the local catalogue server.
#
#   ./tools/publish-samples.sh              # publish at the baseline versions
#   ./tools/publish-samples.sh --bump 5     # publish NEWER versions -> rows flip to "Update"
#
# Typical loop:
#   1. ./tools/publish-samples.sh            then install those APKs on the device
#   2. ./tools/publish-samples.sh --bump 5   the server now offers newer builds
#   3. open LucidAppStore: Lucid Media and Lucid Radio show "Update"
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUMP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bump) BUMP="$2"; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

echo "==> Building sample apps with versionBump=$BUMP"
"$ROOT/gradlew" -p "$ROOT" :samples:app:assembleDebug -PversionBump="$BUMP" -q

APK_DIR="$ROOT/server/apks"
mkdir -p "$APK_DIR"
rm -f "$APK_DIR"/*.apk

FLAVOURS=(
  "media|com.lucid.auto.media|lucid-media"
  "radio|com.lucid.auto.radio|lucid-radio"
  "spotify|com.spotify.music|spotify"
)

updates=()

for entry in "${FLAVOURS[@]}"; do
  IFS='|' read -r flavour pkg prefix <<<"$entry"
  src="$ROOT/samples/app/build/outputs/apk/$flavour/debug/app-$flavour-debug.apk"
  [[ -f "$src" ]] || { echo "missing build output: $src" >&2; exit 1; }

  # Read the real versionCode/versionName straight out of the built APK, so the catalogue can
  # never drift from what the APK actually declares.
  read -r code name < <("$ROOT/tools/apk-version.sh" "$src")
  dest="$APK_DIR/$prefix-$name.apk"
  cp "$src" "$dest"
  printf '    %-24s %-10s versionCode=%-10s -> server/apks/%s\n' \
    "$pkg" "$name" "$code" "$(basename "$dest")"
  updates+=("$pkg=$code=$name=$(basename "$dest")")
done

echo "==> Updating server/data/apps.json"
UPDATES="${updates[*]}" APPS_JSON="$ROOT/server/data/apps.json" python3 - <<'PY'
import json, os, pathlib

data_file = pathlib.Path(os.environ["APPS_JSON"])
catalog = json.loads(data_file.read_text())
by_pkg = {a["packageName"]: a for a in catalog["apps"]}

for chunk in os.environ["UPDATES"].split():
    pkg, code, name, apk = chunk.split("=")
    entry = by_pkg[pkg]
    entry["versionCode"] = int(code)
    entry["versionName"] = name
    entry["apkFile"] = apk

data_file.write_text(json.dumps(catalog, indent=2) + "\n")
print("    wrote", data_file)
PY

echo
echo "Done. Start the server, or reload it if it is already running:"
echo "    cd server && npm start"
echo "    curl -X POST http://localhost:8080/admin/reload"

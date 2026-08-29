#!/usr/bin/env bash
#
# Prints "<versionCode> <versionName>" for an APK, using aapt2 from the installed build-tools.
#
#   ./tools/apk-version.sh path/to/app.apk
#
set -euo pipefail

APK="${1:?usage: apk-version.sh <apk>}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)/aapt2"

if [[ ! -x "$AAPT2" ]]; then
  echo "aapt2 not found under $SDK/build-tools" >&2
  exit 1
fi

"$AAPT2" dump badging "$APK" | awk '
  /^package: / {
    for (i = 1; i <= NF; i++) {
      if ($i ~ /^versionCode=/) { code = $i; gsub(/versionCode=|'\''/, "", code) }
      if ($i ~ /^versionName=/) { name = $i; gsub(/versionName=|'\''/, "", name) }
    }
    print code, name
    exit
  }'

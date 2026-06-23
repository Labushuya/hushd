#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<EOF
Usage: $0 <apk-path> [sha256sums.txt]

Verifies:
  1. apksigner verify --print-certs (signature scheme v2/v3/v4)
  2. SHA-256 of the APK matches the value in sha256sums.txt
  3. Optional: compare against installed APK on a connected device (adb)
EOF
  exit 1
}

[[ $# -ge 1 ]] || usage
APK="$1"
SUMS="${2:-sha256sums.txt}"

[[ -f "$APK" ]] || { echo "APK not found: $APK"; exit 1; }

# --- 1. Signature ---------------------------------------------------------
BT_PATH="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT=$(ls -d "$BT_PATH"/build-tools/*/ 2>/dev/null | sort -V | tail -n1)
[[ -n "$BT" ]] || { echo "ERROR: no Android build-tools in $BT_PATH"; exit 1; }

echo "=== apksigner verify ==="
"${BT}apksigner" verify --verbose --print-certs "$APK"

# --- 2. SHA-256 -----------------------------------------------------------
if [[ -f "$SUMS" ]]; then
  echo
  echo "=== SHA-256 against $SUMS ==="
  EXPECTED=$(grep -E "  $(basename "$APK")\$" "$SUMS" | awk '{print $1}' || true)
  ACTUAL=$(sha256sum "$APK" | awk '{print $1}')
  if [[ -z "$EXPECTED" ]]; then
    echo "WARN: $(basename "$APK") not listed in $SUMS"
  elif [[ "$EXPECTED" == "$ACTUAL" ]]; then
    echo "OK: $ACTUAL"
  else
    echo "FAIL: expected $EXPECTED, got $ACTUAL"
    exit 2
  fi
else
  echo "WARN: $SUMS not found, skipping checksum compare"
fi

# --- 3. Compare against installed APK (optional) --------------------------
if command -v adb >/dev/null && adb get-state 2>/dev/null | grep -q device; then
  PKG=$("${BT}aapt" dump badging "$APK" | grep "package: name=" | sed -E "s/.*name='([^']+)'.*/\1/")
  if [[ -n "$PKG" ]]; then
    echo
    echo "=== Comparing against installed $PKG ==="
    DEV_APK_PATH=$(adb shell pm path "$PKG" 2>/dev/null | head -n1 | sed 's/^package://' | tr -d '\r' || true)
    if [[ -n "$DEV_APK_PATH" ]]; then
      TMP=$(mktemp --suffix=.apk)
      adb pull "$DEV_APK_PATH" "$TMP" >/dev/null
      DEV_SHA=$(sha256sum "$TMP" | awk '{print $1}')
      LOCAL_SHA=$(sha256sum "$APK" | awk '{print $1}')
      rm -f "$TMP"
      if [[ "$DEV_SHA" == "$LOCAL_SHA" ]]; then
        echo "OK: installed APK matches local"
      else
        echo "DIFF: installed=$DEV_SHA local=$LOCAL_SHA (expected after update — verify signing cert above)"
      fi
    fi
  fi
fi

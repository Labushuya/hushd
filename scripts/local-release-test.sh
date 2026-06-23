#!/usr/bin/env bash
set -euo pipefail

# --- Vorflug ---------------------------------------------------------------
ENV_FILE=".env.local"
[[ -f "$ENV_FILE" ]] || { echo "ERROR: $ENV_FILE not found. Copy .env.local.example and fill values."; exit 1; }
if ! grep -qE '^\.env\.local$' .gitignore 2>/dev/null; then
  echo "ERROR: .env.local must be in .gitignore. Aborting before secret leak."; exit 1
fi

# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

: "${SIGNING_KEYSTORE_PATH:?must be set in .env.local}"
: "${SIGNING_KEYSTORE_PASSWORD:?must be set}"
: "${SIGNING_KEY_ALIAS:?must be set}"
: "${SIGNING_KEY_PASSWORD:?must be set}"

[[ -f "$SIGNING_KEYSTORE_PATH" ]] || { echo "ERROR: keystore not at $SIGNING_KEYSTORE_PATH"; exit 1; }

# --- versionCode/Name simulieren ------------------------------------------
VERSION_NAME="${1:-$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || echo 0.0.0-local)}"
VERSION_CODE="${2:-$(git rev-list --count HEAD)}"

export ORG_GRADLE_PROJECT_versionName="$VERSION_NAME"
export ORG_GRADLE_PROJECT_versionCode="$VERSION_CODE"
export SIGNING_KEYSTORE_PATH SIGNING_KEYSTORE_PASSWORD SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD

echo "Building release APK: $VERSION_NAME ($VERSION_CODE)"
./gradlew :app:assembleRelease --stacktrace --no-daemon

APK=$(ls app/build/outputs/apk/release/*.apk | head -n1)
echo
echo "=== Signature verification ==="
BT=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/ | sort -V | tail -n1)
"${BT}apksigner" verify --verbose --print-certs "$APK"

echo
echo "=== SHA-256 ==="
sha256sum "$APK"
echo
echo "Output: $APK"

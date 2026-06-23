#!/usr/bin/env bash
set -euo pipefail

# --- Konfiguration ----------------------------------------------------------
OWNER="Labushuya"
REPO="hushd"
ENV_NAME="production"
KEYSTORE_FILE="release.jks"
KEY_ALIAS="hushd-upload"
KEYSTORE_VALIDITY_DAYS=10000
DNAME="CN=Hushd, OU=Maintainers, O=Open Source, L=Walldorf, ST=BW, C=DE"

# --- Vorflug-Checks ---------------------------------------------------------
command -v keytool   >/dev/null || { echo "keytool not found (JDK)"; exit 1; }
command -v base64    >/dev/null || { echo "base64 not found"; exit 1; }
command -v gh        >/dev/null || { echo "gh-cli not found"; exit 1; }
gh auth status       >/dev/null || { echo "gh not authenticated — run 'gh auth login'"; exit 1; }

if [[ -f "$KEYSTORE_FILE" ]]; then
  echo "ERROR: $KEYSTORE_FILE already exists. Aborting to avoid overwrite."
  echo "Move it aside first, or set KEYSTORE_FILE to a new path."
  exit 1
fi

# --- Passwörter abfragen ----------------------------------------------------
read -r -s -p "Keystore password: " KEYSTORE_PASSWORD; echo
read -r -s -p "Key password (Enter = same as keystore): " KEY_PASSWORD; echo
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

if (( ${#KEYSTORE_PASSWORD} < 16 )); then
  echo "ERROR: keystore password must be >= 16 chars"; exit 1
fi

# --- Keystore generieren ----------------------------------------------------
umask 077
keytool -genkeypair -v \
  -storetype JKS \
  -keystore "$KEYSTORE_FILE" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity "$KEYSTORE_VALIDITY_DAYS" \
  -dname "$DNAME" \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEY_PASSWORD"

chmod 400 "$KEYSTORE_FILE"

# --- Fingerprint anzeigen ---------------------------------------------------
echo
echo "=== Keystore fingerprint (commit this to README) ==="
keytool -list -v -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" \
  -storepass "$KEYSTORE_PASSWORD" | grep -E "SHA-?256|SHA-?1|MD5" || true

# --- Base64 encode ----------------------------------------------------------
KEYSTORE_B64=$(base64 -w 0 < "$KEYSTORE_FILE")

# --- Secrets im production-Environment setzen ------------------------------
echo
echo "=== Setting GitHub Environment secrets (env=$ENV_NAME) ==="
gh secret set SIGNING_KEYSTORE_BASE64   --env "$ENV_NAME" --repo "$OWNER/$REPO" --body "$KEYSTORE_B64"
gh secret set SIGNING_KEYSTORE_PASSWORD --env "$ENV_NAME" --repo "$OWNER/$REPO" --body "$KEYSTORE_PASSWORD"
gh secret set SIGNING_KEY_ALIAS         --env "$ENV_NAME" --repo "$OWNER/$REPO" --body "$KEY_ALIAS"
gh secret set SIGNING_KEY_PASSWORD      --env "$ENV_NAME" --repo "$OWNER/$REPO" --body "$KEY_PASSWORD"

echo
echo "DONE. Backup $KEYSTORE_FILE to a password manager (1Password / KeePass / Bitwarden)"
echo "and verify the fingerprint above against the value committed to README.md."
echo "After backup, securely delete it:"
echo "    shred -u $KEYSTORE_FILE  # Linux"
echo "    rm -P $KEYSTORE_FILE     # macOS"

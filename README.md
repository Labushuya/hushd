<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png" alt="Hushd Logo" width="128" height="128"/>
</p>

<h1 align="center">Hushd</h1>

<p align="center">
  <strong>Autostart killer for HONOR MagicOS (private build)</strong>
</p>

<p align="center">
  <a href="https://developer.android.com/about/versions/11">
    <img src="https://img.shields.io/badge/Android-API%2030%2B-3DDC84?logo=android&logoColor=white" alt="Android API 30+"/>
  </a>
  <a href="https://kotlinlang.org">
    <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.0"/>
  </a>
</p>

---

**Hushd** disables the per-app *Autostart* / *Run in background* toggles in MagicOS battery settings — automatically, in bulk, without root and without ADB. It drives the system UI through an AccessibilityService and gives you back the battery life that aggressive OEM background scheduling silently takes.

## Compatibility

Hushd V1 targets exactly one device family. Other devices and ROMs are explicitly out of scope for V1.

| Vendor | ROM | Versions | Device | Status |
|---|---|---|---|---|
| HONOR | MagicOS | 7.x | HONOR Magic V2 | Supported |
| HONOR | MagicOS | 8.x | HONOR Magic V2 | Supported |
| Any other | Any | Any | Any | Not supported in V1 |

Multi-ROM profiles (MIUI/HyperOS, ColorOS, OneUI, EMUI) are tracked for a later release.

## Permissions explained

Hushd requests exactly the permissions it needs — no more.

| Permission | Why |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | The core mechanism: Hushd drives the MagicOS battery settings screens via the same API screen readers use. Without it, autostart toggles cannot be flipped programmatically without root. |
| `SYSTEM_ALERT_WINDOW` | Draws the overlay HUD so you can see what Hushd is currently doing without losing focus on the settings screen it is automating. |
| `QUERY_ALL_PACKAGES` | Required on API 30+ to list user-installed apps for the picker. Hushd only reads package metadata, never their contents. |
| `POST_NOTIFICATIONS` | Foreground-service notification for bulk runs (Android 13+ requires this even for foreground services). |
| `RECEIVE_BOOT_COMPLETED` | Detects OTA/`Build.FINGERPRINT` changes after reboot to offer a re-run. No autostart from this — the receiver only schedules a deferred check. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets Hushd ask MagicOS not to kill itself mid-bulk-run. Without this, MagicOS will frequently terminate Hushd during long queues. |

Hushd does **not** request `INTERNET`. There is no in-app HTTP client.

## Installation

Hushd is distributed exclusively as a **signed APK on GitHub Releases** of the private repository `Labushuya/hushd`. There is no Play Store listing and no public distribution channel.

### Step 1 — Allow installation from unknown sources

On Android 8 and newer, the "unknown sources" permission is granted **per app** (not globally):

1. Download the APK from the latest release of `Labushuya/hushd` using the browser or file manager you intend to install from.
2. Open the downloaded file. MagicOS will show a dialog *"For your security, your phone is not allowed to install unknown apps from this source"*.
3. Tap **Settings** in that dialog, then enable **Allow from this source** for the file manager / browser you used.
4. Go back and tap **Install**.

On older MagicOS builds you may instead find the toggle under **Settings → Security & privacy → More security settings → Install unknown apps**.

### Step 2 — Install and launch

Tap **Install**, then **Open**. The first-run onboarding will guide you through the remaining permissions.

## Verification

Every release ships a signed APK and a GitHub-issued build attestation. Before installing on the device, verify both.

### Verify the APK signature with `apksigner`

```bash
apksigner verify --verbose --print-certs hushd-0.1.0-release.apk
```

The output must list a single signer whose SHA-256 certificate digest matches the fingerprint pinned in your local notes for the `Labushuya/hushd` release key. If the digest does not match, **do not install the APK**.

### Verify the GitHub build attestation

```bash
gh attestation verify hushd-0.1.0-release.apk \
  --repo Labushuya/hushd \
  --predicate-type https://slsa.dev/provenance/v1
```

The command must report `Verification succeeded` and bind the artifact to the workflow run that built the release tag. If it does not, the APK was not produced by the official pipeline — discard it.

## Privacy

Hushd is a strictly local tool.

- No network code. The `INTERNET` permission is not in the manifest.
- No telemetry. No crash reports to third parties. No analytics SDKs.
- No cloud sync. The journal and disable list live in a Room database in app-private storage and are wiped when you uninstall Hushd.
- No screen content outside MagicOS settings is read. The AccessibilityService's `packageNames` filter is hardcoded to the MagicOS settings packages.

## Build

The build is reproducible from a clean checkout with JDK 21 and the Android SDK Build-Tools 35.0.0.

```bash
git clone https://github.com/Labushuya/hushd
cd hushd
./gradlew assembleRelease
```

The signed APK lands in `app/build/outputs/apk/release/`. Signing material is supplied through environment variables documented in `scripts/setup-secrets.sh`; without them the build produces an unsigned APK suitable only for local inspection.

Before pushing changes:

```bash
./gradlew check
```

This runs detekt, ktlint, Android Lint and the unit-test suite.

<p align="center">
  <sub>All rights reserved — private project</sub>
</p>

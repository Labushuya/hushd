# Changelog

All notable changes to **Hushd** are documented here.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-06-24

### Added
- AppList screen: loads all installed apps (user + system), filter chips, search, per-app selection
- Bulk automation flow: select multiple apps, tap "Autostart deaktivieren" — BulkAutostopEngine iterates
- Overlay progress UI: floating card shows current package and progress (KillApps-style)
- Permission flow: checks AccessibilityService + overlay permission, guides user to enable them
- Disclaimer screen: one-time acceptance flow on first launch

### Fixed  
- V1 placeholder screen replaced with functional UI

## [0.1.0] - 2026-06-23

### Added
- Initial scaffold: Gradle multi-module project, AccessibilityService, OverlayService,
  BulkAutostopEngine, HONOR MagicOS profile, GitHub Actions CI + Release workflows.
- App branding (Hushd wordmark with daemon-dot accent), adaptive + themed icon assets.
- Signed-release pipeline with SHA-256 sums and (optional) Sigstore build-provenance attestations.
- Local pre-push guardrail against force-pushes to `main`.

### Known limitations (V1)
- UI surface is a placeholder — the app launches and the AccessibilityService can be
  enabled, but the AppList / BulkEngine UI lands in the next release.
- CycloneDX SBOM generation is deferred to V2 (plugin DSL incompatibility).
- ktlint, detekt and Android Lint run in advisory mode (reports uploaded, not gating).

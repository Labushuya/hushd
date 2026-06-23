# Workflow Notes — Hushd Initial Scaffold

Build-Notizen vom ersten Materialisierungs-Lauf. Werden vom Setup-Skript NICHT erzeugt; bleiben für Folge-Tasks als Referenz im Repo.

## Quelle

Generiert aus `C:\Code\claude\autostop-prompt\segment-1.md` … `segment-6.md` (zusammengeführt in `PROMPT.md`, 240 KB). Materialisierung über 6 parallele Subagents + 1 Reconcile-Pass für Modul-Struktur.

## Globale Anpassungen vs. Original-Prompt

Das Original-Prompt nannte die App "Idle" / "Autostart Manager" mit Lizenz GPL-3.0-or-later und F-Droid-Ziel. Wir bauen privat:

| Aspekt | Original | Hushd |
|---|---|---|
| App-Name | Idle / Autostart Manager | **Hushd** |
| Package | `dev.labushuya.idle` / `app.idle.android` / `de.delos.autostartmgr` / `dev.autostartmgr.app` | **`dev.labushuya.hushd`** |
| Repo | autostart-manager / idle | **Labushuya/hushd** (private) |
| Lizenz | GPL-3.0-or-later | **None — private project** |
| F-Droid | Ziel-Markt | Entfernt |
| Play Store | de facto out | unverändert: out |
| Disclaimer-Form | 6 Absätze Legal-Text | **1 Absatz, 5-7 Sätze + Checkbox** |
| CONTRIBUTING.md | ausführlich | **5-Zeilen-Stub** |
| SECURITY.md | vorhanden | **Nicht erzeugt** |
| CODE_OF_CONDUCT.md | vorhanden | **Nicht erzeugt** |
| LICENSE | GPL-3.0 Text | **Nicht erzeugt** |
| Tagline | "Block app autostart on HONOR MagicOS" | "Autostart killer for HONOR MagicOS (private build)" |
| Themen-Farben Logo | — | Background `#0F172A`, Foreground `#E2E8F0`, Accent `#EF4444` |

## Package-Renames (Code)

Im gesamten Code wurden folgende Package-Roots zu `dev.labushuya.hushd` normalisiert:

- `dev.autostartmgr.app.*` → `dev.labushuya.hushd.*`
- `de.delos.autostartmgr.*` → `dev.labushuya.hushd.*`
- `dev.autostartmgr.service.accessibility.*` → `dev.labushuya.hushd.service.accessibility.*`
- `dev.autostartmgr.service.overlay.*` → `dev.labushuya.hushd.service.overlay.*`
- `dev.autostartmgr.core.common.log.*` → `dev.labushuya.hushd.core.common.log.*`

Interne Klassen-Namen blieben absichtlich erhalten (Identität liegt im Package, nicht im Symbol):

- `AutostopAccessibilityService` (kein Rename zu `HushdAccessibilityService`)
- `BulkAutostopEngine` (kein Rename zu `BulkHushdEngine`)
- `OverlayService`, `BootCompletedReceiver`, `AppListViewModel`, `HonorMagicOsProfile`, `OemProfileResolver`

Begründung: Refactor-Kosten ohne Funktionsänderung. Wenn später jemand das vereinheitlichen will, ist es ein reines Find-Replace + Pfad-Anpassung.

## Modul-Layout — Konflikt-Resolution

Segment-1 listete den Tree mit dash-Namen (`core-data`, `core-domain`, …), Segment-2 (Gradle-Build) verwendete colon-nested (`core:data`, `core:automation`, …), Segment-4 (Kotlin-Code) verwendete teils dritte Varianten (`core-engine`, `core-oem`).

**Gewinner:** colon-nested, weil `app/build.gradle.kts` (aus Segment-2) bereits `implementation(projects.core.common)` etc. referenziert. Reconcile-Pass hat alle Kotlin-Sources entsprechend verschoben:

- `core-engine/` → `core/automation/` (Package `core.engine` → `core.automation`)
- `core-oem/` → `core/automation/oem/` (Package `core.oem` → `core.automation.oem`)
- `service-accessibility/` → `service/accessibility/`
- `service-overlay/` → `service/overlay/`
- `feature-applist/` → `feature/applist/`

Imports innerhalb `AutostopAccessibilityService.kt`, `OverlayService.kt`, `OverlayContent.kt`, `BulkAutostopEngine.kt` entsprechend nachgezogen. Grep auf `core.engine` / `core.oem` ist clean.

## Disclaimer (gekürzt)

Original-Prompt hatte einen ~30-Zeilen-Legal-Disclaimer. Wir haben das auf **ein Absatz pro Sprache** reduziert (englisch + deutsch), plus Checkbox-Label "Ich verstehe und akzeptiere die Risiken" / "I understand and accept the risks". Strings in `app/src/main/res/values/strings.xml` und `app/src/main/res/values-de/strings.xml` unter Keys `disclaimer_body`, `disclaimer_accept_label`, `disclaimer_title`.

## Was NICHT erzeugt wurde (bewusst)

- `LICENSE` — privates Projekt, kein Open-Source-Vertrieb
- `SECURITY.md` — privater 2-User-Scope, kein Disclosure-Pfad nötig
- `CODE_OF_CONDUCT.md` — keine Community
- F-Droid-Konfiguration / -Workflows
- Reproducible-Build aktive Konfiguration (steht als V3-Optional in Roadmap)

## Was NICHT erzeugt wurde (TODO / offen)

Diese Dinge blockieren den ersten Build und müssen vor `./gradlew check` materialisiert werden:

### Hoch-Priorität (Build-blockierend)

1. **Build-Logic / Convention-Plugins:** Segment-1 listete `build-logic/convention/AndroidApplicationConventionPlugin.kt`, `KotlinAndroidConventionPlugin.kt`, `SigningConventionPlugin.kt`. **Code wurde in keinem Segment geliefert.** Nur `build-logic/settings.gradle.kts` + `build-logic/convention/build.gradle.kts` als Stub vorhanden. Aktuelles `app/build.gradle.kts` referenziert KEINE Convention-IDs — Plugins werden direkt via Version-Catalog geladen. Stub-Modul ist also okay, aber wenn der spätere CI-Build Convention-Plugins erwartet, müssen die noch geschrieben werden.

2. **Per-Module-Kotlin-Sources:** `core/common`, `core/data`, `core/ui`, `feature/automation` haben aktuell KEINE Kotlin-Source-Files. Modul-Stubs sind erzeugt (build.gradle.kts + leeres AndroidManifest.xml), aber als Empty-AAR. App kompiliert grundsätzlich, aber die ViewModels / Repositories / Compose-Themes, die das App-Modul erwartet, fehlen. → Folge-Task: V1.0-Implementierung dieser Module aus den Segmenten ergänzen (Segment-4 hatte hier nur Teil-Ausschnitte).

3. **`gradle/verification-metadata.xml`:** Wird in `gradle.properties` referenziert aber nicht erzeugt (Segment-2 hatte nur Pfad-Hinweise). User muss einmal `./gradlew --write-verification-metadata sha256,pgp help` laufen lassen sobald die anderen Module Sources haben.

4. **`lint-baseline.xml`:** Wird in `app/build.gradle.kts` (`baseline = file("lint-baseline.xml")`) referenziert. Wird beim ersten `./gradlew lint` automatisch erzeugt — kein manueller Schritt nötig.

5. **`CHANGELOG.md`:** `release.yml` validate-tag und `scripts/release.sh` lesen daraus. Noch nicht angelegt → Folge-Task vor erstem Release.

### Mittlere Priorität

6. **AndroidManifest-Inhalte der Submodule:** `service/accessibility/src/main/AndroidManifest.xml` ist aktuell ein leerer `<manifest/>`-Stub. Service-Deklaration mit `BIND_ACCESSIBILITY_SERVICE`-Permission + Intent-Filter muss nachgezogen werden (oder in `app/src/main/AndroidManifest.xml` aufgenommen — Manifest-Merge regelt's). Aktuell hat das App-Manifest die Service-Deklaration bereits, also nur Cleanup-TODO.

7. **`docs/`-Verzeichnis:** Segment-1 listete ADRs (`0001-signing-strategy.md`, `0002-canPerformGestures-false.md`), `KEYSTORE_SETUP.md`, `KEY_ROTATION.md`, `RELEASE_PROCESS.md`, `RESTRICTED_SETTINGS.md`, `MAGICOS_COMPATIBILITY.md`, `ARCHITECTURE.md`. Nicht in den Build-Segmenten geliefert. → Folge-Task #17 (User-Anleitung) deckt das ggf. ab.

8. **`schemas/`-Verzeichnis:** `app/build.gradle.kts` enthält `room { schemaDirectory("$projectDir/schemas") }`. Wird beim ersten Room-Compile erzeugt — kein manueller Schritt.

9. **`profiles/`-Modul:** Segment-1 listete ein `:profiles`-Modul mit JSON-Assets (`autolaunch-labels.json`, `critical-packages.json`, `canary-apps.json`, `magicos-component-probe-order.json`). Wir haben das vereinfacht: nur `app/src/main/res/raw/honor_magicos.json` (aus Segment-4) und der OemProfileResolver lädt direkt aus `res/raw`. Wenn OEM-Profile später erweitert werden, könnte Migration zu eigenem Modul Sinn machen.

10. **`build-logic/convention/`** ist eine leere `kotlin-dsl`-Submodule — keine Convention-Plugins registriert. Wenn das später gebraucht wird: Files unter `build-logic/convention/src/main/kotlin/` + `gradlePlugin { plugins { register(...) } }` im build.gradle.kts.

### Niedrig (Cosmetic / Tooling)

11. **Logo-Assets:** Aktuell nur Placeholder-VectorDrawable (rotes "H" auf dunklem BG). Task #12 generiert die echten Assets aus `logo-hushd-v2.svg`.

12. **Notification-Title-Strings im OverlayService/Receiver:** Segment-4 hat hardcodierte "Autostart Manager"-Strings als `setContentTitle()`-Argumente. Sollte auf `getString(R.string.app_name)` umgestellt werden — aktuell wird "Autostart Manager" angezeigt statt "Hushd". Cosmetic-Bug, kein Blocker.

13. **Gradle-Wrapper-JAR:** Per `curl` von `raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar` heruntergeladen (43 583 Bytes). Funktioniert, ist aber kein offizieller Distributionspfad. **Empfehlung:** Wenn Gradle 8.x lokal installiert ist, einmal `gradle wrapper --gradle-version 8.11.1 --distribution-type bin` laufen lassen um JAR + gradlew/gradlew.bat sauber zu regenerieren.

14. **Signing-Secrets:** `scripts/setup-secrets.sh` ist materialisiert, aber Keystore + `SIGNING_*` Secrets in GitHub müssen separat per Task #14 angelegt werden. Keystore-Datei wird absichtlich NICHT in diesem Scaffold-Schritt erzeugt.

## Conflict-Resolution-Log

Stellen, an denen Segment-Inhalte widersprüchlich waren und wir Entscheidungen getroffen haben:

| Konflikt | Entscheidung |
|---|---|
| Modul-Layout colon vs. dash | colon-nested (segment-2 / app/build.gradle.kts gewinnt) |
| App-Name "Autostart Manager" in Notifications | Belassen für jetzt (cosmetic TODO #12) |
| Mehrere Package-Wurzeln in Segmenten (`de.delos.autostartmgr`, `dev.autostartmgr.app`, `dev.labushuya.idle`, `de.delos.autostartmgr` …) | Alle auf `dev.labushuya.hushd` normalisiert |
| `:profiles` als eigenes Modul vs. `res/raw`-Lade-Pfad | `res/raw` im `:app`-Modul (Segment-4-Code lädt so) |
| `BulkAutostopEngine` package `core.engine` vs. Modul `:core:automation` | Modul gewinnt; Code-Package auf `core.automation` umgestellt |
| Original disclaimer 6 Absätze vs. private 1 Absatz | 1 Absatz (siehe Anpassungen) |

## Welche Segment-Inhalte sind in welcher Datei gelandet?

- **Segment-2 (Build):** `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `.gitignore`
- **Segment-3 (Manifest/XML):** `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/{accessibility_service_config,data_extraction_rules,locales_config,network_security_config}.xml`
- **Segment-4 (Kotlin):** Service-/Engine-/ViewModel-/Profile-Klassen unter den finalen Modul-Pfaden (siehe Modul-Layout-Section) + `app/src/main/res/raw/honor_magicos.json`
- **Segment-5 (CI/Scripts):** `.github/workflows/{ci,release,release-drafter}.yml`, `.github/{dependabot,release-drafter}.yml`, `.github/CODEOWNERS`, `.commitlintrc.json`, `.env.local.example`, `scripts/{setup-secrets,setup-branch-protection,local-release-test,verify-apk,release}.sh`
- **Segment-6 (Docs/Disclaimer):** `README.md`, `CONTRIBUTING.md` (Stub), `app/src/main/res/values{,-de}/strings.xml`, `.github/ISSUE_TEMPLATE/{bug-report,device-profile-request}.yml`, `.github/PULL_REQUEST_TEMPLATE.md`
- **Placeholder-Logo:** `app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`, `app/src/main/res/values/ic_launcher_background.xml`, `app/src/main/res/drawable/ic_launcher_{foreground,monochrome}.xml`

## Was als Nächstes (Folge-Tasks)

Siehe Task-Tracker:

- Task #12 — Logo-Assets generieren
- Task #13 — GitHub-Repo `Labushuya/hushd` private anlegen + push
- Task #14 — Signing-Keystore + GitHub-Secrets
- Task #15 — Branch-Protection auf main
- Task #16 — Ersten CI-Lauf grün bekommen (hierfür müssen die "Hoch-Priorität"-TODOs oben adressiert sein)
- Task #17 — User-Anleitung schreiben

---

*Generiert: 2026-06-23 — Initial-Scaffold-Pass von Hushd.*

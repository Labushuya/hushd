# Hushd — Quickstart für Labushuya

> Was Du jetzt selbst tust, nachdem das Repo, die Secrets, das CI-Skelett und das Branding bereits eingerichtet sind.

---

## Was bereits steht (von mir erledigt)

- ✅ **Repo:** [github.com/Labushuya/hushd](https://github.com/Labushuya/hushd) (private)
- ✅ **Initial-Commits:** 5 Commits auf `main` (scaffold, branding, build-fixes, app-classes, ci-fixes)
- ✅ **Signing-Keystore:** generiert, 4096-bit RSA, 10 000 Tage gültig
  - **SHA-256:** `98:F0:C7:0A:81:D6:20:44:A0:A0:47:E0:1D:47:5D:41:C4:68:5A:18:C5:DA:33:9D:4F:22:05:3B:E3:E0:92:F1`
  - Backup-Files unter `C:\Code\claude\hushd-secrets-backup\` — **bitte in Deinen Passwort-Manager schieben und dann das Backup-Verzeichnis löschen**
- ✅ **GitHub-Environment-Secrets:** `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` (im Environment `production`)
- ✅ **Branding:** Logo (Wortmark `hushd` + Daemon-Punkt + Stopp-Unterstrich) in allen Mipmap-Dichten + Adaptive Icon + Themed Icon
- ✅ **CI/CD:** Workflows `.github/workflows/ci.yml` und `release.yml` deployed, Dependabot aktiv, Release-Drafter konfiguriert

---

## Was Du als nächstes tun musst

### 1. Backup absichern (sofort!)

Die Files in `C:\Code\claude\hushd-secrets-backup\` enthalten Deinen Signing-Key. **Verlust = keine zukünftigen Updates derselben App mehr möglich.**

1. Öffne `C:\Code\claude\hushd-secrets-backup\.env.local` (enthält die Passwörter)
2. Lege im Passwort-Manager (KeePassXC, Bitwarden, 1Password — Deine Wahl) einen Eintrag `hushd signing` an mit:
   - **Keystore-Datei:** Attach `release.jks` als Datei-Anhang ODER speichere base64-encoded
   - **Keystore-Passwort:** aus `.env.local` (Zeile `SIGNING_KEYSTORE_PASSWORD=...`)
   - **Key-Alias:** `hushd-upload`
   - **Key-Passwort:** aus `.env.local` (`SIGNING_KEY_PASSWORD=...`)
   - **SHA-256 Fingerprint:** s.o.
3. Dann das Backup-Verzeichnis löschen:
   ```bash
   rm -rf C:/Code/claude/hushd-secrets-backup
   ```

### 2. Android Studio installieren

- **Version:** Android Studio Ladybug Feature Drop (2024.2.2) oder neuer
- **Download:** https://developer.android.com/studio
- **JDK:** das gebündelte JBR-Temurin-21 reicht. Falls separates JDK gewünscht: [Temurin 21 LTS](https://adoptium.net/temurin/releases/?version=21)
- Beim ersten Start in Android Studio:
  - SDK Manager öffnen → Android 14 (API 34) Platform SDK installieren
  - SDK Manager → Build-Tools 34.0.0 installieren
  - SDK Manager → Android SDK Command-line Tools

### 3. Repo lokal klonen

```bash
cd C:/Code/claude
git clone https://github.com/Labushuya/hushd
cd hushd
```

In Android Studio: **File → Open → C:\Code\claude\hushd** → Trust Project → Gradle Sync läuft an.

> **Falls Sync scheitert** mit "verification-metadata"-Hinweis: ignorieren — die Datei wird erst in V2 aktiviert.

### 4. HONOR Magic V2 für USB-Debugging vorbereiten

1. **Einstellungen → System → Über das Telefon** — 7× auf die **Build-Nummer** tippen bis "Du bist jetzt Entwickler" erscheint
2. **Einstellungen → System → Entwickleroptionen** öffnen
3. **USB-Debugging** einschalten (Bestätigungs-Dialog akzeptieren)
4. *(Optional aber empfohlen)* **USB-Debugging (Sicherheitseinstellungen)** auch einschalten — erlaubt der App, Inputs zu simulieren
5. Kabel anschließen → am Smartphone **"USB-Debugging zulassen"** mit Häkchen "Immer von diesem Computer zulassen"
6. In Bash prüfen ob das Gerät erkannt wird:
   ```bash
   adb devices
   # Erwartet: <serial>  device
   ```

### 5. Debug-Build und Install

In Android Studio:
- Geräte-Dropdown (oben rechts) zeigt jetzt **HONOR Magic V2**
- Grünen Run-Button drücken — Gradle baut, APK wird auf das Gerät geschoben, App startet

Oder via Kommandozeile:
```bash
cd C:/Code/claude/hushd
./gradlew assembleDebug installDebug
```

### 6. App-Setup auf dem Magic V2

Beim ersten Start zeigt die App den **Onboarding-Flow**:

1. **Welcome-Screen** — kurzer Disclaimer-Text, "Ich verstehe und akzeptiere"-Checkbox
2. **AccessibilityService aktivieren** — Deep-Link öffnet die System-Einstellungen → **Eingabehilfen** → **Hushd** suchen → einschalten → **Hinweis-Dialog mit "OK"** bestätigen
   - **Wichtig:** Hier gibt MagicOS eine extra Warnung "Diese App kann Bildschirminhalte sehen und steuern". Das ist gewollt — wir brauchen genau diese Permission. Mit "Erlauben" bestätigen.
3. **Overlay-Permission aktivieren** — Deep-Link öffnet "Über anderen Apps anzeigen" → Hushd → einschalten
4. **Battery-Optimization-Whitelist** — Hushd zur Liste "Akku-Optimierung nicht anwenden" hinzufügen (sonst killt MagicOS den AccessibilityService nach ein paar Minuten)
5. **Done** — die App-Liste wird angezeigt

### 7. Erst-Test (vorsichtig!)

**Wähle EINE harmlose App** für den ersten Test (z.B. Taschenrechner, Wetter, Notizen) — **niemals als Erst-Test eine System-App!**

1. Diese App in der Liste antippen → "Autostart deaktivieren"
2. Hushd öffnet im Hintergrund den MagicOS-Settings-Screen für diese App, navigiert zum Autostart-Toggle und deaktiviert ihn
3. Im Overlay siehst Du den Live-Progress
4. Nach Abschluss: prüfe manuell in den Einstellungen, ob der Toggle wirklich aus ist
5. Optional: Smartphone neu starten und prüfen ob diese App nicht mehr im Hintergrund läuft

### 8. Erstes Release (signierte APK)

Wenn der Erst-Test grün war, kannst Du das erste offizielle Release bauen:

```bash
cd C:/Code/claude/hushd
git checkout main
git pull

# Release-Skript bumped Version, commited, taggt, pusht
chmod +x scripts/release.sh
./scripts/release.sh 0.1.0
```

Das pusht den Tag `v0.1.0`. GitHub Actions baut dann automatisch eine **signierte Release-APK**, hängt sie ans Release `https://github.com/Labushuya/hushd/releases/tag/v0.1.0` an, plus `sha256sums.txt`.

### 9. APK auf dem Smartphone installieren (drüber bügeln)

- Mobiler Browser → https://github.com/Labushuya/hushd/releases/latest
- (Auf private Repo musst Du im GitHub-Browser auf dem Smartphone eingeloggt sein)
- APK herunterladen → öffnen → installieren
- **Bei Updates** (V0.2.0, V0.3.0, …): selber Weg, Android erkennt den gleichen Signing-Key und tut "drüber bügeln" — Deine App-Daten und Einstellungen bleiben erhalten

---

## Troubleshooting

### Gradle-Sync scheitert mit "Unable to find...":
- Internet-Connection prüfen
- `Build → Clean Project` → erneut Sync

### `adb devices` zeigt das Magic V2 nicht:
- USB-Modus auf "File Transfer (MTP)" umstellen
- Anderes USB-Kabel versuchen (manche sind charge-only)
- `adb kill-server && adb start-server`

### AccessibilityService stoppt nach Reboot:
- MagicOS Battery-Saver hat den Service gekillt. Hushd selbst in "Akku-Optimierung nicht anwenden" packen.
- Falls trotzdem: Hushd in "Geschützte Apps" (Honor-spezifisch, Einstellungen → Batterie → App-Start) auf "Manuell" stellen und alle 3 Toggles aktivieren.

### Bulk-Run hängt bei einer App:
- Hushd zeigt im Journal welche App. Die Settings-Activity dieser App hat ggf. eine andere UI-Hierarchie. Bug-Report im Repo öffnen mit dem Journal-Log.

### Update-APK lässt sich nicht über die alte installieren:
- "App nicht installiert. Signaturkonflikt": Du hast die Updates aus zwei verschiedenen Builds (z.B. lokal-debug + Release). Erst alte Version deinstallieren, dann neu installieren.

---

## Wichtige Pfade

- **Repo lokal:** `C:\Code\claude\hushd\`
- **Repo remote:** https://github.com/Labushuya/hushd
- **Fach-Prompt:** `C:\Code\claude\autostop-prompt\PROMPT.md` (240 KB Originaldokument für Referenz)
- **CI-Status:** https://github.com/Labushuya/hushd/actions
- **Releases:** https://github.com/Labushuya/hushd/releases

## V2-Roadmap (offene Punkte)

Dokumentiert in `C:\Code\claude\hushd\WORKFLOW_NOTES.md` und in den `TODO(v2):`-Kommentaren im Code:

- Echte Compose-UI für AppList + Bulk-Engine-Progress (aktuell nur Placeholder-Screen)
- Room-Schema für persistente Journal-Einträge
- CycloneDX-SBOM-Generation im Release-Workflow (DSL-Migration auf cyclonedx-gradle-plugin 1.10+)
- Multi-ROM-Profile (MIUI, ColorOS, OneUI, EMUI)
- Shizuku-Fallback für direktes `pm disable-user`
- Backup/Restore der Disable-Liste

---

*Erstellt automatisch — wenn etwas unklar ist, frag nach.*

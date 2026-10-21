# Claude Watch – Claude Code auf der Galaxy Watch 7

Claude-Code-Freigaben und Chat-Nachrichten direkt am Handgelenk. Wenn Claude
eine Erlaubnis braucht (z. B. `npm test` ausführen oder eine Datei ändern),
vibriert die Uhr. **Schütteln = Erlauben**, oder tippe auf ✓ / ✕. Antworten
von Claude landen als Chat auf der Uhr, und du kannst per Sprache oder
Schnellantwort zurückschreiben.

```
Claude Code ──Hook──▶ Relay-Server (dein PC) ◀──WLAN/LTE── Wear-OS-App (Galaxy Watch 7)
   ▲                         │                               │
   └── allow / deny ◀────────┘◀────── Schütteln / ✓ / ✕ ─────┘
```

| Teil | Ordner | Was es macht |
| --- | --- | --- |
| Hook | `hooks/claude_watch_hook.py` | Hängt an `PermissionRequest`, `Stop`, `Notification`, `UserPromptSubmit` und spricht mit dem Relay. |
| Relay | `relay/server.py` | Kleiner HTTP-Server (nur Python-Standardbibliothek). Hält offene Anfragen und Nachrichten. Enthält unter `/web` auch eine Handy-Weboberfläche. |
| Watch-App | `wear/` | Kotlin/Compose-App für Wear OS 3+ (getestet gedacht für Galaxy Watch 7, Wear OS 5/6). |

## Sicherheit eingebaut

- **Nur wenn die Uhr verbunden ist.** Ist keine Uhr online, kommt sofort die
  normale Abfrage im Terminal – du wartest nie umsonst.
- **Timeout:** Antwortet niemand innerhalb von 5 Minuten (einstellbar), fällt
  die Anfrage zurück aufs Terminal.
- **Riskante Befehle brauchen einen Tipp.** `rm -rf`, `git push --force`,
  `git reset --hard`, `sudo`, `curl … | sh` usw. lassen sich nicht per
  Schütteln freigeben (Regex über `no_shake` anpassbar).
- **Schütteln startet nur einen Countdown** (1,5 s, mit Vibration). Ein Tipp
  bricht ab. Geschüttelt wird nur gezählt, während die Freigabe-Ansicht offen ist.
- Alles ist mit einem geheimen Token geschützt.

## Einrichtung

### 1. Relay-Server starten (auf dem PC mit Claude Code)

```bash
# Ein zufälliges Token erzeugen und merken
python3 -c "import secrets; print(secrets.token_urlsafe(24))"

python3 relay/server.py --token DEIN_TOKEN            # lauscht auf 0.0.0.0:8787
```

Die Uhr muss den PC erreichen können. Zuhause im selben WLAN reicht die
LAN-Adresse des PCs, z. B. `http://192.168.0.42:8787`. Für unterwegs siehe
[Von unterwegs](#von-unterwegs).

### 2. Hook in Claude Code einrichten

```bash
mkdir -p ~/.claude/hooks
cp hooks/claude_watch_hook.py ~/.claude/hooks/
cp hooks/claude-watch.example.json ~/.claude/claude-watch.json   # Token eintragen!
```

Dann den Inhalt von [`hooks/settings.example.json`](hooks/settings.example.json)
in `~/.claude/settings.json` übernehmen (bei bestehenden `hooks` zusammenführen).
Unter Windows `python` statt `python3` und den vollen Pfad zum Skript verwenden.

`~/.claude/claude-watch.json`:

| Schlüssel | Standard | Bedeutung |
| --- | --- | --- |
| `url` | `http://127.0.0.1:8787` | Wo der Hook das Relay findet (meist localhost). |
| `token` | – | Gleiches Token wie beim Relay. |
| `permission_timeout` | `300` | Sekunden, die auf die Uhr gewartet wird. |
| `reply_wait` | `0` | Sekunden, die Claude nach einer Antwort auf deine Watch-Antwort wartet (siehe Chat). |
| `no_shake` | siehe Skript | Regex für Befehle, die nicht per Schütteln freigegeben werden dürfen. |

Alle Werte gehen auch als Umgebungsvariablen, z. B. `CLAUDE_WATCH_TOKEN`.

### 3. App bauen

**Fertige APK:** Jeder Push baut die App per GitHub Actions. Unter
*Actions → Build → Artifacts* liegt `claude-watch-apk`.

**Selbst bauen** (Android Studio oder Android-SDK nötig):

```bash
cd wear
# optional: Standardwerte in die App einbauen, spart das Tippen auf der Uhr
cat > local.properties <<EOF
sdk.dir=/pfad/zum/Android/sdk
claudeWatch.url=http://192.168.0.42:8787
claudeWatch.token=DEIN_TOKEN
EOF
./gradlew assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
```

### 4. Auf die Galaxy Watch 7 installieren

1. Auf der Uhr: *Einstellungen → Info zur Uhr → Software* → 5× auf
   *Softwareversion* tippen (Entwickleroptionen an).
2. *Einstellungen → Entwickleroptionen*: **ADB-Debugging** und
   **Debugging über WLAN** einschalten. Uhr und PC im selben WLAN.
3. Unter *Debugging über WLAN → Neues Gerät koppeln* stehen IP, Port und Code:
   ```bash
   adb pair 192.168.0.55:41234        # Kopplungscode eingeben
   adb connect 192.168.0.55:39876     # Port aus "Debugging über WLAN"
   adb install app-release.apk
   ```
4. App **Claude Watch** öffnen → Benachrichtigungen erlauben →
   *Einstellungen*: Relay-URL und Token prüfen → zurück → **Verbindung** an.

**Wichtig bei Samsung:** In der Galaxy-Wearable-App bzw. auf der Uhr unter
*Apps → Claude Watch → Akku* auf **Nicht eingeschränkt** stellen, sonst
beendet One UI Watch die Verbindung im Hintergrund.

## Benutzung

- **Freigabe:** Uhr vibriert, die Freigabe-Ansicht geht auf (Werkzeug,
  Projekt, Befehl/Datei). Schütteln → Countdown → erlaubt. Oder ✓ / ✕.
  Auch direkt aus der Benachrichtigung heraus mit *Erlauben* / *Ablehnen*.
- **Chat:** Wenn Claude fertig ist, kommt die letzte Antwort als Nachricht.
  *Antworten* (Sprache, Tastatur oder Schnellantwort wie „Ja, weiter“) schickt
  deinen Text an die Sitzung:
  - Mit `reply_wait` > 0 (z. B. `120`) wartet Claude nach jeder Antwort so
    lange auf dich und macht direkt mit deiner Antwort weiter.
  - Mit `reply_wait` = 0 wird die Antwort beim nächsten Stopp von Claude
    übernommen.
- **Vom Handy:** `http://PC-IP:8787/web?token=DEIN_TOKEN` im Handy-Browser
  öffnen – zeigt dieselben Freigaben und den Chat (ohne Schütteln).

## Von unterwegs

Die Uhr nutzt das Netz des Handys (Bluetooth) oder eigenes WLAN/LTE. Damit
das Relay auch außerhalb des Heimnetzes erreichbar ist, z. B.:

- **Cloudflare Tunnel:** `cloudflared tunnel --url http://localhost:8787`
  liefert eine `https://…trycloudflare.com`-Adresse → in der App als URL eintragen.
- Oder das Relay auf einem eigenen Server/VPS hinter HTTPS betreiben und
  `url` im Hook auf diese Adresse setzen.

Das Token schützt den Zugriff; nutze außerhalb des LANs trotzdem immer HTTPS.

## Entwicklung

```bash
python3 -m unittest discover -s tests -v   # Relay + Hook End-to-End-Tests
```

Relay-API (alle Endpunkte unter `/api` brauchen `Authorization: Bearer TOKEN`):

| Methode | Pfad | Zweck |
| --- | --- | --- |
| `POST` | `/api/permission` | Hook legt Anfrage an (409, wenn keine Uhr online) |
| `GET` | `/api/permission/{id}?wait=50` | Hook wartet auf Entscheidung |
| `POST` | `/api/permission/{id}/decision` | Uhr: `{"decision": "allow"\|"deny"}` |
| `DELETE` | `/api/permission/{id}` | Hook gibt auf (Timeout) |
| `POST` | `/api/message` | Hook schickt Chat-Nachricht |
| `POST` / `GET` | `/api/reply` | Uhr antwortet / Hook holt Antwort ab |
| `GET` | `/api/events?since=N&wait=50` | Uhr: Long-Polling für neue Ereignisse |
| `GET` | `/api/state` | Uhr: aktueller Stand beim Verbinden |

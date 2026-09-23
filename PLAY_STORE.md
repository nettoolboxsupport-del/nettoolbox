# Weg in den Play Store

Stand: 18.08.2026. Diese Datei sammelt alles, was zwischen dem heutigen Build
und einer Veröffentlichung liegt — getrennt danach, wer es erledigen kann.

## Technisch erledigt

| Anforderung | Stand |
|---|---|
| App Bundle als Format | `:app:bundleRelease` ist ohne Zusatzkonfiguration verfügbar |
| targetSdk innerhalb der Frist | 36, aktuell |
| 16-KB-Seitenausrichtung (Pflicht seit 11/2025) | alle sechs `.so` auf `0x4000` geprüft |
| R8 aktiv, Regeln im Release verifiziert | ja, `usage.txt` und `mapping.txt` geprüft |
| Signierung über externen Keystore | Gerüst steht, Schlüssel fehlt |
| Lizenzen und Namensnennung in der App | Über-Bildschirm, OSM und OpenCelliD hervorgehoben |
| Keine Telemetrie, keine Werbe-SDKs | Vorgabe der Spezifikation, eingehalten |
| Baseline Profile | ✓ im AAB nachgewiesen (`BUNDLE-METADATA/.../baseline.prof`) |
| Onboarding mit Berechtigungserklärung | ✓ |
| App-Widget | ✓ |
| Übersetzungen vollständig | ✓ 393 Strings, DE und EN, geprüft |
| Datenschutzerklärung verfasst | ✓ `PRIVACY.md`, noch nicht veröffentlicht |

## Der wichtigste Rückbau: ACCESS_BACKGROUND_LOCATION ist weg

Das war die schwerste Hürde. Google verlangt für diese Berechtigung ein
**separates Prüfverfahren mit eingereichtem Video**, und sie ist ein häufiger
Ablehnungsgrund.

Gebraucht wird sie nicht. Androids Dokumentation zum Foreground-Service-Typ
`location` sagt wörtlich, dass Hintergrund-Standort nur nötig ist, um einen
solchen Dienst **aus dem Hintergrund zu starten**. Der Drive-Test wird
ausschließlich aus der Oberfläche gestartet — geprüft: kein WorkManager, kein
AlarmManager, kein BroadcastReceiver, kein `BOOT_COMPLETED`. Läuft der Dienst,
behält er den Standortzugriff auch dann, wenn der Nutzer die App verlässt.

Ein Test bewacht die Entscheidung (`PermissionBundleTest`), und der Grund steht
im Manifest. **Falls jemals ein zeitgesteuerter Drive-Test dazukommt, muss das
neu bewertet werden** — das ist genau der Fall, der die Berechtigung braucht.

Ebenfalls entfernt:

- `READ_EXTERNAL_STORAGE` und `WRITE_EXTERNAL_STORAGE` — kamen über den
  Manifest-Merge aus einer Abhängigkeit. Der Export läuft über SAF und braucht
  keine Speicherberechtigung. Ein Netzwerkwerkzeug, das Dateizugriff verlangt,
  weckt genau den Verdacht, den es vermeiden sollte.
- `REORDER_TASKS` — tut die App nicht.
- `dataSync` beim Drive-Test-Dienst — falsche Deklaration. Der Dienst überträgt
  nichts über das Netz. Seit Android 14 muss jeder Typ einzeln gerechtfertigt
  werden, und `dataSync` wird am strengsten geprüft.

## Was nur du erledigen kannst

### 1. Upload-Schlüssel erzeugen

```
keytool -genkeypair -v -keystore nettoolbox-release.jks -alias nettoolbox -keyalg RSA -keysize 4096 -validity 10000
```

Dann `keystore.properties` im Projektstamm anlegen (steht in `.gitignore`):

```
storeFile=C:/sicherer/pfad/nettoolbox-release.jks
storePassword=...
keyAlias=nettoolbox
keyPassword=...
```

**Jks und Passwörter außerhalb des Projekts sichern.** Geht der Upload-Schlüssel
verloren, ist das beim Play Store ein Support-Fall.

### 2. Datenschutzerklärung veröffentlichen

Pflicht, ohne URL keine Freigabe. Der Text liegt fertig als `PRIVACY.md` bei,
mit Robin Fodor als Verantwortlichem und nettoolboxsupport@gmail.com als
Kontakt. Er muss jetzt nur noch unter einer öffentlich erreichbaren Adresse
liegen — GitHub Pages genügt, und die URL gehört dann in die Play Console.

Diese Adresse muss erreichbar bleiben: Google prüft sie, und Nutzer wenden sich
über sie an dich.

### 3. Angaben zur Datensicherheit im Play Console

Das Formular ist Pflicht. Für diese App sind die Antworten kurz:

- **Werden Daten erhoben oder geteilt?** Nein.
- **Werden Daten übertragen?** Nein. Messdaten bleiben auf dem Gerät.
- **Verschlüsselung bei der Übertragung?** Entfällt, es wird nichts übertragen.
- **Löschung durch Nutzer möglich?** Ja, über die App und über das Löschen der
  App-Daten.

Wichtig zur Abgrenzung: Standort und Mobilfunkdaten werden **verarbeitet**, aber
nicht **erhoben** im Sinne des Formulars — erheben heißt, sie verlassen das
Gerät. Der Export ist nutzerausgelöst und geht an ein Ziel, das der Nutzer
selbst wählt.

### 4. Foreground Services rechtfertigen

Seit Android 14 verlangt Google für jeden Typ eine Begründung. Textvorschläge:

**`location` (Drive-Test-Aufzeichnung):**
> Die App zeichnet auf Wunsch des Nutzers Mobilfunk-Messwerte zusammen mit der
> Position auf, um Netzabdeckung zu dokumentieren. Der Nutzer startet die
> Aufzeichnung ausdrücklich; sie läuft weiter, während das Display aus ist oder
> eine andere App im Vordergrund liegt, weil eine Messfahrt sonst abbricht. Eine
> dauerhafte Benachrichtigung zeigt den Zustand und erlaubt das Beenden.

**`dataSync` (iperf3-Server):**
> Die App kann als iperf3-Server arbeiten, damit ein Gegenüber im Netz die
> Durchsatzleistung zu diesem Gerät messen kann. Dazu muss ein lauschender
> Socket offen bleiben, auch wenn die App nicht im Vordergrund ist — andernfalls
> bricht Android die laufende Messung ab. Der Nutzer startet den Server
> ausdrücklich und beendet ihn über die Benachrichtigung.

### 5. Zu erwartende Rückfragen

- **`READ_PHONE_STATE`** — nötig für Mobilfunk-Identität und Messwerte. Das ist
  der Kern der App und leicht zu begründen.
- **Portscanner und IP-Scanner** — legitime Netzwerkdiagnose. Der rechtliche
  Hinweis beim ersten Gebrauch (Spezifikation Abschnitt 9) ist bereits
  umgesetzt und hilft hier. Nicht als „Sicherheitswerkzeug" bewerben.
- **`NEARBY_WIFI_DEVICES` ohne `neverForLocation`** — bewusst so, weil der
  Wi-Fi-Analyzer aus Scan-Ergebnissen tatsächlich Ortsbezug ableitet. Diese
  Angabe muss im Formular konsistent sein.

### 6. Store-Material

- App-Symbol 512×512 PNG
- Feature-Grafik 1024×500
- Mindestens zwei Screenshots je unterstützter Formfaktor
- Kurzbeschreibung (80 Zeichen), Vollbeschreibung (4000)
- Inhaltsfreigabe-Fragebogen, Zielgruppe

## Build für den Upload

```
.\gradlew :app:bundleRelease
```

Ergebnis: `app/build/outputs/bundle/release/app-release.aab`

Das Bundle liefert pro Gerät nur die passende ABI. Bei drei ABIs und MapLibre
mit rund 11 MB je ABI ist das der Unterschied zwischen 18,1 MB APK und etwa
8 MB Auslieferung.

## Noch offen im Code

Nichts, was einer Veröffentlichung im Weg steht. Die verbleibenden Punkte sind
Ausbau, kein Mangel — aufgelistet in den jeweiligen Phasennotizen:
iperf3-Client-Optionen (parallele Streams, bidirektional, Live-Diagramm),
Wi-Fi-Heatmap, OUI-Herstellerauflösung, OpenCelliD-Online-API, Sektor-Keulen
auf der Karte, DNS-Rückwärtsauflösung, ICMP-Sweep im IP-Scanner.

## Stand: interner Test läuft (21.08.2026)

| Schritt | Stand |
|---|---|
| Upload-Schlüssel erzeugt und gesichert | ✓ CN=Robin Fodor, gültig bis 2054 |
| Quelltext öffentlich | ✓ github.com/nettoolboxsupport-del/nettoolbox |
| Datenschutzerklärung erreichbar | ✓ nettoolboxsupport-del.github.io/nettoolbox/PRIVACY |
| Play-Console-Konto | ✓ |
| App-Inhalte (alle Deklarationen) | ✓ |
| Store-Eintrag mit Texten und Grafiken | ✓ Standardsprache Englisch (en-US) |
| Interner Test veröffentlicht | ✓ Versionscode 1, 21.08. 13:45 |
| Interner Test aktualisiert | ✓ Versionscode 2 (1.1.0), 03.09. — Dateiserver, siehe PHASE8_NOTES.md |
| Version 1.2.0 vorbereitet | Versionscode 3 — serielle Konsole, Review-Fixes (PHASE9_NOTES.md), Store-Texte und Datenschutzerklärung aktualisiert |

## Der Weg zur Veröffentlichung: 14 Tage Mindestfrist

**Persönliche Entwicklerkonten, die nach dem 13.11.2023 angelegt wurden,
dürfen nicht direkt in Produktion veröffentlichen.** Google verlangt vorher:

- einen **geschlossenen** Test — der interne zählt dafür **nicht**
- mit **mindestens 12 Testern**
- die **14 Tage ununterbrochen** angemeldet sind
- danach Antrag auf Produktionszugriff, Prüfung in rund sieben Tagen

Steigt ein Tester zwischendurch aus, beginnt seine Frist von vorn.

Das ist der Grund, warum der interne Test zuerst kam: Er ist sofort verfügbar
und zeigt, ob das Bundle überhaupt angenommen wird — aber er bringt einen
keinen Tag näher an die Veröffentlichung. **Zwölf Tester zusammenzubekommen ist
die eigentliche Hürde**, nicht die Technik.

### Was nach der Installation zu prüfen ist

Drei Pfade, an denen die R8-Keep-Regeln hängen. Lokal bereits bestätigt, aber
das von Play **neu signierte und aufgeteilte** Bundle ist eine andere Datei:

1. Nativer Ping → `IcmpNative`
2. iperf3-Client → `Iperf3Native`
3. SSH-Verbindung, einmal Enter → `VtermNative`, jschs Algorithmen, Bouncy Castle

### Bekannte Eigenheit

Der Opt-in-Link eines frisch veröffentlichten internen Tests liefert oft
stundenlang eine 404-Seite, bis Google die Store-Seite angelegt hat. Kein
Fehler. Vorher prüfen: Ist die Testerliste dem Track **zugewiesen**, und ist der
Browser mit genau der eingetragenen Adresse angemeldet?

## Noch offen im Code


Nichts, was einer Veröffentlichung im Weg steht. Die verbleibenden Punkte sind
Ausbau, kein Mangel — aufgelistet in den jeweiligen Phasennotizen:
iperf3-Client-Optionen (parallele Streams, bidirektional, Live-Diagramm),
Wi-Fi-Heatmap, OUI-Herstellerauflösung, OpenCelliD-Online-API, Sektor-Keulen
auf der Karte, DNS-Rückwärtsauflösung, ICMP-Sweep im IP-Scanner.

## Der eigentliche Engpass

Alles Technische ist fertig. Was fehlt, sind ausschließlich Schritte, die ein
Google-Konto und deine Entscheidung brauchen:

1. Upload-Schlüssel erzeugen und sicher ablegen
2. `PRIVACY.md` öffentlich erreichbar machen und die URL notieren
3. Play-Console-Konto anlegen (einmalig 25 USD)
4. Store-Material erstellen (Symbol, Feature-Grafik, Screenshots, Texte)
5. Formulare ausfüllen — die Antworten stehen oben
6. `.\gradlew :app:bundleRelease` und hochladen

Empfehlung für den ersten Upload: als **interner Test** veröffentlichen, nicht
gleich produktiv. Dann prüft Google die Formalien, ohne dass die App öffentlich
sichtbar wird, und du kannst die von Play signierte Fassung selbst installieren.

# Phase 3 – WLAN-Analyzer, Teil 1

Umgesetzt: Scan-Pipeline mit Drosselungs-Handling, Netzliste mit Filter, Suche und
Sortierung, Kanal-Empfehlung für 2,4 GHz, erster Einsatz von `PermissionGate`.

**Noch offen:** Kanalbelegungsdiagramm, Signalverlauf pro BSSID, Verbindungsdetails,
Watchlist, Heatmap.

## Nicht verifiziert

Der Build wurde hier nicht ausgeführt. Abnahme:

```bash
.\gradlew.bat :app:assembleDebug test
```

**Der Emulator ist für dieses Modul weitgehend nutzlos** — er hat kein echtes
WLAN-Radio und liefert bestenfalls ein Pseudonetz. Phase 3 braucht ein echtes
Gerät.

## Entscheidungen

### Die Drosselung wird abgebildet, nicht umgangen

Vier Scans pro zwei Minuten, ab Android 10 nur in den Entwickleroptionen
abschaltbar. Die App kann das nicht umgehen, also modelliert sie es exakt:
`ScanThrottle` rechnet aus, wann der nächste Scan erlaubt ist, der Knopf zeigt
einen Countdown, und daneben steht der Deep-Link in die Entwickleroptionen.

Die Alternative wäre ein Knopf, der still nichts tut — genau das, was die meisten
WLAN-Apps machen.

### Passiver Modus ist kein Zusatz, sondern die Hauptquelle

Der `SCAN_RESULTS_AVAILABLE`-Broadcast feuert, wenn **irgendeine** App oder das
System scannt. Die Liste bleibt dadurch frisch, während das eigene Scan-Budget
erschöpft ist. Ohne diesen Kanal stünde der Analyzer zwei Minuten am Stück still.

### Ohne Standortberechtigung liefert die Plattform eine leere Liste

Keinen Fehler, keine Exception — eine leere Liste. Deshalb sperrt der Screen über
`PermissionGate` ab, statt „keine Netze" anzuzeigen. Das ist der Unterschied
zwischen „hier ist nichts" und „ich darf nichts sehen".

### Frequenz-, Kanal- und Sicherheitsauswertung sind rein und getestet

Das sind die Stellen, die **still** falsch sind statt laut kaputt: eine um eins
verschobene Kanalnummer sieht in einer Liste plausibel aus, und ein 6-GHz-Netz als
5 GHz auszuweisen schickt jemanden an das falsche Radio.

Getestet inklusive der Sonderfälle: Kanal 14 im 2,4-GHz-Band bricht das 5-MHz-
Raster, und 6-GHz-Kanal 2 liegt unterhalb der Basisfrequenz.

### Kanalempfehlung gewichtet nach Signalstärke

Ein starker Nachbar stört mehr als drei schwache zwei Räume weiter. Die Gewichtung
ist quadratisch in der Feldstärke; bei Gleichstand gewinnt der niedrigere Kanal,
damit zwei Scans im selben Raum dieselbe Antwort geben.

### Wi-Fi-Generation nur, wo die Plattform sie kennt

`getWifiStandard()` gibt es erst ab Android 11. Darunter bleibt es bei „?" — aus
der Kanalbreite eine Generation abzuleiten wäre eine Erfindung.

### Fehlende Capability-Zeichenkette heißt UNKNOWN, nicht OPEN

Ein Netz als „offen" auszuweisen, über das nichts bekannt ist, wäre eine
Sicherheitsaussage, die die Daten nicht hergeben.

## Neue Tests

| Testklasse | deckt ab |
|---|---|
| `WifiChannelsTest` | Frequenz→Kanal in allen drei Bändern, Kanal 14, 6-GHz-Kanal 2, Überlappung, Kanalempfehlung mit Gewichtung |
| `WifiSecurityParserTest` | WPA/WPA2/WPA3/SAE-Transition/OWE/WEP/offen, Enterprise, WPS, fehlende Zeichenkette |
| `ScanThrottleTest` | Fenstergrenzen, fünfter Scan, veraltete Zeitstempel, mehr als vier im Fenster |

---

# Phase 3, Teil 2

Umgesetzt: Kanalbelegungsdiagramm, Signalverlauf, Verbindungsdetails, Merkliste.
Die vier Ansichten liegen unter einer Modus-Leiste im WLAN-Tab.

## Entscheidungen

### Kanaldiagramm als Bogen über dem belegten Spektrum

Jedes Netz wird als quadratische Bezierkurve über der Breite gezeichnet, die es
tatsächlich belegt — Mittenfrequenz aus `centerFreq0`, Breite aus `channelWidth`.
Überlappende Bögen **sind** die Interferenz, und das beantwortet „warum ist das
Netz langsam" schneller als jede Liste.

Gezeichnet wird von schwach nach stark, damit ein starkes Netz nicht hinter einem
schwachen verschwindet. Die Farbe leitet sich aus der BSSID ab und bleibt
deshalb zwischen zwei Scans dieselbe — sonst könnte man ein Netz im Diagramm nicht
verfolgen.

### Signalverlauf bleibt im Arbeitsspeicher

`SignalHistoryTracker` hält 120 Messwerte je BSSID und höchstens 256 BSSIDs. Das
ist, was man beim Ausrichten einer Antenne beobachtet, und fünf Minuten später
wertlos. In Room geschrieben würde es die Datenbank mit Daten füllen, nach denen
niemand zweimal fragt — Messdaten gehören in die Drive-Test-Sitzung aus Phase 4.

Das Diagramm behält je Intervall den **schlechtesten** Wert (`BucketStrategy.MIN`).
Beim Ausrichten interessiert der Einbruch, nicht der Durchschnitt.

Ohne Merkliste werden die drei stärksten Netze gezeichnet, damit die Ansicht nicht
ohne Grund leer ist.

### Merkliste liegt in den Einstellungen

`UserSettings.watchedBssids`. Der Sinn einer Merkliste ist, dass sie das Verlassen
des Standorts überlebt — im Arbeitsspeicher wäre sie sinnlos.

### Verbindungsdetails aus drei Quellen

Keine Einzelquelle hat das ganze Bild: `WifiManager` kennt das Funkteil,
`LinkProperties` die Adressierung, `NetworkCapabilities` die Frage, ob die
Strecke überhaupt ins Internet reicht. Der Captive-Portal-Status erklärt eine
„online, aber nichts lädt"-Meldung in einer Zeile.

`getConnectionInfo()` ist ab API 31 deprecated; der Ersatz liefert `WifiInfo` über
einen Netzwerk-Callback. Der Wechsel lohnt, sobald der Screen laufend aktualisieren
soll statt eine Momentaufnahme zu zeigen — im Code markiert.

## Was aus Phase 3 offen bleibt

- **Heatmap mit Grundriss-Import.** In der Spezifikation als optional geführt
  (4.2). Braucht Bildimport, manuelles Setzen von Messpunkten und IDW-Interpolation
  — ein eigenes Arbeitspaket.
- **OUI-Hersteller-Lookup.** Braucht die IEEE-Liste als Asset; die BSSIDs dafür
  sind hier erstmals vorhanden.
- **Benachrichtigung bei Merklisten-Treffern.** Die Liste filtert bereits, aber es
  gibt noch keine Meldung, wenn ein gemerktes Netz auftaucht oder verschwindet.

## Nächster Schritt

Phase 4, Mobilfunk: `TelephonyCallback`-Pipeline, Live-Ansicht mit Serving Cell und
Nachbarzellen, ARFCN/Band-Tabellen, Multi-SIM, danach der Logging-Service und die
Karte.

# Phase 2c – Export, Verlauf, Port-Scanner

Umgesetzt: SAF-Dateiexport, Verlaufsansicht pro Werkzeug mit Wiederholen-Funktion,
Port-Scanner samt Rechtshinweis.

**Noch offen aus Phase 2:** IP-Scanner und HTTP/TLS-Inspector.

## Nicht verifiziert

Der Build wurde hier nicht ausgeführt. Abnahme:

```bash
.\gradlew.bat :app:assembleDebug test
```

## Festgelegte Entscheidungen

### SSH-Terminal: Termux-Bibliothek, App wird GPLv3

Entschieden am 17.08.2026. Das SSH-Terminal (Spezifikation 4.4, Phase 6) nutzt die
Terminal-Emulation aus Termux statt einer Eigenentwicklung. Damit laufen `vim`,
`htop`, `nano` und `mc` von Anfang an.

**Folge: NetToolbox insgesamt steht dann unter GPLv3.** Das ist für Sideload und
F-Droid unproblematisch, schließt aber eine proprietäre Variante dauerhaft aus.
Die `LICENSE`-Datei wird gesetzt, sobald die Abhängigkeit tatsächlich eingebunden
wird — heute enthält der Code noch keinen GPL-Bestandteil, und eine Lizenz zu
deklarieren, die für den aktuellen Stand nicht gilt, wäre falsch.

**Reihenfolge:** SSH kommt nach Phase 4, wie ursprünglich geplant. Vorher: Rest von
Phase 2, WLAN-Analyzer, Mobilfunk.

## Entscheidungen dieser Phase

### Export über SAF, nicht über einen Pfad

`rememberFileExporter` startet `CreateDocument`. Die App braucht damit keine
Speicherberechtigung, und ohne ausdrückliche Auswahl des Nutzers wird nichts
geschrieben — das ist die Bedingung aus Abschnitt 9 („Export ausschließlich
nutzerinitiiert"). Der Inhalt wird erst erzeugt, nachdem ein Ziel gewählt wurde;
ein abgebrochener Dialog kostet nichts.

Der Dateiname trägt einen sortierbaren Zeitstempel:
`nettoolbox-portscan-20260817-142530.csv`.

### Verlauf über eine gemeinsame Hülle

`ToolScaffold` gibt jedem Werkzeug denselben Titelbalken mit demselben Zugang zum
Verlauf. Damit ist die History keine Funktion, die manche Werkzeuge zufällig haben.

Subnetzrechner und Wake-on-LAN bekommen bewusst keinen: der Rechner rechnet bei
jedem Tastendruck neu, und WOL zeigt die zuletzt geweckten Geräte bereits als
Chips auf dem Screen selbst.

### Wiederholen füllt aus, startet aber nicht

Ein Verlaufseintrag öffnet das Werkzeug mit den gespeicherten Parametern. Der Lauf
startet nicht von selbst — bei einem Port-Scan wäre das ein Netzwerkzugriff, den
niemand ausgelöst hat. Die Lauf-ID reist als Navigationsargument (`Long` mit
Default `-1`, weil nullable Primitive einen eigenen NavType bräuchten).

### Port-Scan: TCP-Connect, und das ist sichtbar

Ein Half-Open-Scan braucht Raw Sockets, die es ohne Root nicht gibt. Jede Probe ist
also ein vollständiger Handshake und steht im Log des Ziels. Genau deshalb steht
vor dem ersten Scan der Rechtshinweis aus Abschnitt 9, dessen Bestätigung in den
Einstellungen gespeichert wird.

Der Banner wird nur gelesen, nie provoziert: es wird nichts gesendet. Ein
Probe-Payload würde aus einem passiven Scan eine aktive Interaktion machen.

`PortSpec.MAX_PORTS_PER_SCAN` liegt bei 8192. Ein Tippfehler wie `1-65535` soll
nicht zum Vollscan fremder Netze werden.

### Begrenzte Parallelität

Standard 64 gleichzeitige Sockets, hart gedeckelt bei 256. Ein Telefon, das 1000
Sockets gleichzeitig öffnet, misst überwiegend sein eigenes Dateideskriptor-Limit
statt des Netzes.

## Neue Tests

`PortSpecTest` — Einzelports, Bereiche, Duplikate, Leerzeichen, Bereich rückwärts,
Werte außerhalb 1–65535, das Scan-Limit, Konsistenz der Voreinstellungen.

## HTTP/TLS-Inspector

### Weiterleitungen werden von Hand verfolgt

Der gemeinsame OkHttp-Client steht auf `followRedirects(false)`. Die Kette **ist**
der Zweck des Werkzeugs — ein Client, der sie still auflöst, verbirgt genau das,
wofür man den Inspector öffnet. Außerdem hat jeder Sprung eigene TLS-Parameter und
ein eigenes Zertifikat.

Schleifen werden über die Menge der besuchten URLs erkannt, die Kette bei zehn
Sprüngen gekappt. Beides wird im UI benannt statt stillschweigend abgeschnitten.

### Ohne Schema wird HTTPS angenommen

`http://` vorzusetzen würde für eine Seite, die über TLS einwandfrei erreichbar
ist, eine Klartextverbindung melden. Ein Schema, das der Inspector nicht spricht
(`ftp://`), ist ein Fehler und wird nicht überschrieben. Getestet in
`HttpUrlNormalizerTest`.

### Ehrlicher User-Agent

`NetToolbox/0.1 (+network diagnostics)`. Sich als Browser auszugeben würde die
Antworten mancher Server verändern und das Messergebnis zur Fiktion machen.

### Ablaufdatum wird gerechnet, nicht nur angezeigt

Die Restlaufzeit in Tagen ist die Zahl, wegen der man das Werkzeug öffnet.
Abgelaufen und "noch nicht gültig" sind getrennte Zustände — bei einem frisch
ausgerollten Zertifikat mit falscher Systemzeit ist der Unterschied die Diagnose.
Warnschwelle 30 Tage.

### Teilergebnisse bleiben erhalten

Bricht die Kette beim dritten Sprung ab, werden die ersten beiden trotzdem gezeigt.
Verworfen wird nur, wenn gar kein Schritt zustande kam.

## IP-Scanner

### Kein ARP — und vier Verfahren als Ersatz

`/proc/net/arp` ist ab Android 10 durch SELinux gesperrt. Die App sieht die
Nachbartabelle nicht und kann keine MAC-Adressen lernen. Kompensiert wird das
durch vier Verfahren, die sich ergänzen:

| Verfahren | findet |
|---|---|
| TCP-Probe | alles mit einem offenen Port |
| SSDP | Drucker, NAS, Medienempfänger, IP-Kameras — oft ohne offenen TCP-Port |
| NetBIOS | Namen von Windows-Rechnern |
| mDNS | Namen von Apple-, Linux- und IoT-Geräten |

Ergebnisse werden pro IP **zusammengeführt**, nicht ersetzt: mDNS kennt einen
Namen aber keine Ports, die TCP-Probe kennt Ports aber keinen Namen. Jedes für
sich liefert einen halben Host. Die Quelle steht an jedem Treffer — „nur über
SSDP gefunden" sagt dem Leser, dass der Host auf Multicast antwortet, aber keinen
offenen TCP-Port hat.

### Keine Laufzeitberechtigung nötig

Standortzugriff verlangt Android für WLAN-*Scanergebnisse*, nicht für Sockets ins
eigene Subnetz. `NsdManager` braucht ihn ebenfalls nicht. Der Multicast-Lock ist
eine normale Manifest-Berechtigung ohne Dialog. `PermissionGate` kommt erst beim
WLAN-Analyzer zum Einsatz.

### Multicast-Lock ist Pflicht

Ohne ihn filtert der WLAN-Treiber Multicast-Pakete, bevor sie die App erreichen —
mDNS und SSDP finden dann stillschweigend gar nichts. Er wird für den gesamten
Scan gehalten und im `finally` freigegeben.

### NetBIOS: ein Socket für das ganze Subnetz

254 Datagramme raus, dann ein Lauschfenster — statt 254 Sockets mit je einem
Timeout. UDP braucht keinen Handshake, also kostet ein /24 einen Bruchteil.

Die Namenskodierung (jedes Byte in zwei Nibbles, je um `A` versetzt) ist rein und
getestet, ebenso das Überspringen der Gruppeneinträge: der Workgroup-Name ist auf
jedem Host derselbe und wäre als Maschinenname wertlos.

### mDNS mit fester Typliste statt Meta-Abfrage

Abweichung von Abschnitt 3.5: statt `_services._dns-sd._udp` zu enumerieren, wird
eine feste Liste gängiger Diensttypen abgefragt. Die Meta-Abfrage wird von Androids
Resolver uneinheitlich beantwortet, und ein Browse ohne Ergebnis ist nicht von
einem Netz ohne Dienste zu unterscheiden. Die feste Liste liefert ein
deterministisches Ergebnis.

`NsdManager.resolveService` ist ab API 34 deprecated; der Ersatz
`registerServiceInfoCallback` existiert auf minSdk 28 nicht. Im Code markiert,
auszutauschen sobald minSdk auf 34 steigt.

### Subnetze über /22 werden abgelehnt

Ein /16 wären 65.000 Proben. Wo eine so große Maske auftaucht, ist es fast immer
ein VPN oder eine Punkt-zu-Punkt-Strecke, kein LAN, das man durchsuchen will.

### Noch nicht drin

- **OUI-Hersteller-Lookup.** Braucht die komprimierte IEEE-Liste als Asset. MAC-
  Adressen sind ohnehin nur bei WLAN-BSSIDs und manueller Eingabe bekannt, also
  gehört das zum WLAN-Analyzer in Phase 3.
- **ICMP-Sweep.** Ein `ping`-Prozess pro Host wäre bei einem /24 zu teuer; mit dem
  nativen ICMP-Modul aus Phase 5 wird das ein sinnvoller zusätzlicher Layer.

## Damit ist Phase 2 abgeschlossen

Alle sieben Werkzeuge aus Abschnitt 4.3, die ohne NDK möglich sind, stehen:
Ping, DNS, Subnetzrechner, WOL, Port-Scanner, IP-Scanner, HTTP/TLS-Inspector.
Traceroute und iperf3 bleiben Phase 5, weil sie das native Modul brauchen.

## Nächster Schritt

Phase 3: WLAN-Analyzer — Scan-Pipeline mit Drosselungs-Handling, Liste,
Kanalbelegungsdiagramm, Signalverlauf, Verbindungsdetails. Dort greift erstmals
`PermissionGate`.

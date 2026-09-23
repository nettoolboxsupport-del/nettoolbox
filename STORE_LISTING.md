# Store-Eintrag — Texte zum Kopieren

Alles, was die Play Console an Text verlangt. Zeichenlimits sind geprüft, nicht
geschätzt.

**Ein Grundsatz zieht sich durch alle Texte:** NetToolbox wird als
**Diagnosewerkzeug** beschrieben, nie als Sicherheits- oder Analysewerkzeug.
Portscanner und IP-Scanner sind legitime Netzwerkdiagnose; als
„Sicherheitswerkzeug" beworben landen sie dagegen in einer Kategorie, die
Google gesondert prüft und häufig ablehnt. Die Formulierungen sind bewusst
sachlich und ohne Superlative.

---

## Kurzbeschreibung (max. 80 Zeichen)

**Deutsch** (69 Zeichen):

```
Netzwerkdiagnose, SSH, serielle Konsole und Dateiserver für Techniker
```

**Englisch** (76 Zeichen):

```
Network diagnostics, SSH and serial console, and a TFTP/FTP/SFTP file server
```

---

## Vollbeschreibung (max. 4000 Zeichen)

Stand 1.2.0: mit serieller Konsole und Dateiserver. "Kein Server hinter
dieser App" wurde zu "kein Server des Entwicklers" präzisiert, seit die App
selbst einer sein kann.

**Deutsch** (3304 Zeichen):

```
NetToolbox ist ein Diagnosewerkzeug für Netzwerktechniker — für den Außendienst, das Rechenzentrum und überall dort, wo man wissen muss, was das Netz gerade tatsächlich tut.

MOBILFUNK
Bedienzelle und Nachbarzellen mit allen Messwerten, die das Gerät hergibt: RSRP, RSRQ, SINR, RSSI und mehr, je nach Funktechnik. Zuordnung von ARFCN zu Frequenzband, Erkennung von 5G NSA, Netzbetreiber und Zellkennungen. Messfahrten mit Positionsbezug lassen sich aufzeichnen und später als Datei ausgeben.

WLAN
Netzsuche mit Kanaldiagramm über 2,4, 5 und 6 GHz, Signalverlauf über die Zeit, Beobachtungsliste für einzelne Netze, vollständige Verbindungsdetails mit Kanalbreite, Verschlüsselung, IP, Gateway, DNS und MTU.

WERKZEUGE
Ping über drei Wege: das System-Binary, unprivilegierte ICMP-Sockets oder TCP-Verbindungszeit. Traceroute. DNS-Abfragen von A bis CAA gegen mehrere Resolver gleichzeitig, auch über DNS-over-HTTPS und DNS-over-TLS. Portscanner mit Vorgaben oder eigenen Bereichen. IP-Scanner, der das lokale Netz über TCP, mDNS, SSDP und NetBIOS absucht. HTTP- und TLS-Inspektor mit Weiterleitungskette, TLS-Version und Zertifikatslaufzeit. Subnetzrechner für IPv4 und IPv6. Wake-on-LAN.

DURCHSATZ
Echtes iperf3, im Quelltext eingebunden — kein Nachbau. Als Client gegen einen Server im Netz, oder das Gerät selbst als Server, damit eine Gegenstelle zu ihm messen kann.

SSH
Ein vollwertiges Terminal mit VT100- und xterm-Emulation. Die Prüfung des Hostschlüssels lässt sich nicht abschalten, und ein geänderter Schlüssel wird deutlich von einem unbekannten unterschieden. Verbindungsprofile, Zusatztasten für Strg, Alt, Tab und die Pfeile.

SERIELLE KONSOLE
Dasselbe Terminal über ein USB-Konsolenkabel oder einen USB-Seriell-Adapter: FTDI, Prolific PL2303, Silicon Labs CP210x, WCH CH34x und CDC/ACM-Geräte, darunter der USB-Konsolenport an Netzwerkgeräten. Baudraten-Vorlagen, BREAK für die Wiederherstellung beim Booten, zeilenweises Einfügen mit Pause, damit das Gerät mitkommt, und ein optionaler Mitschnitt.

DATEISERVER
Ein Ordner auf dem Gerät, bereitgestellt über TFTP, FTP/FTPS, SFTP und klassisches SCP — um Firmware und Konfigurationen ohne Laptop auf Switches und Router zu bringen. Konten mit Lese- oder Schreibrecht, ein Live-Protokoll aller Verbindungen und Übertragungen, ein Dateiexplorer mit MD5-, SHA-1- und SHA-256-Prüfsummen und eine automatische Abschaltung, damit eine Freigabe nie versehentlich weiterläuft.

KARTE
Messfahrten und importierte Zellstandorte auf einer Karte, die auf OpenStreetMap aufsetzt.

DEINE DATEN BLEIBEN BEI DIR
Keine Telemetrie. Kein Konto. Kein Server des Entwicklers hinter dieser App. Messwerte liegen auf dem Gerät, und Daten verlassen es nur, wenn du einen Export auslöst oder selbst den Dateiserver startest. Es sind keine Analyse- oder Werbebibliotheken eingebaut.

QUELLOFFEN
Der vollständige Quelltext steht unter der Apache-Lizenz 2.0 öffentlich zur Verfügung. Was die App tut, lässt sich nachlesen statt glauben.

HINWEIS ZUR NUTZUNG
Portscanner und IP-Scanner bauen echte Verbindungen zu den geprüften Zielen auf und sind in deren Protokollen sichtbar. Setze sie nur in Netzen ein, für die du eine Berechtigung hast. Die App weist beim ersten Gebrauch darauf hin.

Kartendaten © OpenStreetMap-Mitwirkende. Zelldaten © OpenCelliD (CC-BY-SA).
```

**Englisch** (3065 Zeichen):

```
NetToolbox is a diagnostic toolkit for network engineers — for field work, for the data centre, and for anywhere you need to know what the network is actually doing right now.

CELLULAR
Serving cell and neighbours with every metric the device reports: RSRP, RSRQ, SINR, RSSI and more, depending on the radio technology. ARFCN to frequency band mapping, 5G NSA detection, operator and cell identifiers. Drive tests with position data can be recorded and exported to a file.

WI-FI
Scanning with a channel diagram across 2.4, 5 and 6 GHz, signal history over time, a watchlist for individual networks, and full connection details including channel width, security, IP, gateway, DNS and MTU.

TOOLS
Ping over three paths: the system binary, unprivileged ICMP sockets, or TCP connect timing. Traceroute. DNS queries from A to CAA against several resolvers at once, including DNS-over-HTTPS and DNS-over-TLS. Port scanner with presets or custom ranges. IP scanner that sweeps the local network over TCP, mDNS, SSDP and NetBIOS. HTTP and TLS inspector showing the redirect chain, TLS version and certificate validity. Subnet calculator for IPv4 and IPv6. Wake-on-LAN.

THROUGHPUT
Real iperf3, vendored from source — not a reimplementation. As a client against a server on your network, or with the device acting as the server so a peer can measure towards it.

SSH
A full terminal with VT100 and xterm emulation. Host key verification cannot be switched off, and a changed key is clearly distinguished from an unknown one. Connection profiles, plus extra keys for Ctrl, Alt, Tab and the arrows.

SERIAL CONSOLE
The same terminal over a USB console cable or a USB-to-serial adapter: FTDI, Prolific PL2303, Silicon Labs CP210x, WCH CH34x and CDC/ACM devices, including the USB console port on network equipment. Baud presets, BREAK for boot-time recovery, line-by-line paste with a pause so the device keeps up, and an optional session log.

FILE SERVER
A folder on the device, served over TFTP, FTP/FTPS, SFTP and legacy SCP — for getting firmware and configurations onto switches and routers without a laptop. Accounts with read-only or read-write access, a live log of every connection and transfer, a file explorer with MD5, SHA-1 and SHA-256 checksums, and an automatic stop so a share is never left running by accident.

MAP
Drive tests and imported cell positions on a map built on OpenStreetMap.

YOUR DATA STAYS WITH YOU
No telemetry. No account. No developer server behind this app. Measurements are stored on the device, and data only leaves it when you start an export or run the file server yourself. There are no analytics or advertising libraries.

OPEN SOURCE
The complete source is published under the Apache License 2.0. What the app does can be read rather than believed.

A NOTE ON USE
Port and IP scanners open real connections to the targets they check and are visible in their logs. Use them only on networks you are authorised to scan. The app says so before the first scan.

Map data © OpenStreetMap contributors. Cell data © OpenCelliD (CC-BY-SA).
```

---

## Kategorie und Einordnung

| Feld | Wert |
|---|---|
| App oder Spiel | App |
| Kategorie | **Tools** (Deutsch: Tools) |
| Tags | Netzwerk, Dienstprogramme, Entwicklertools |
| Enthält Werbung | **Nein** |
| In-App-Käufe | **Nein** |

---

## Inhaltsfreigabe (IARC-Fragebogen)

Die App enthält nichts von dem, wonach der Fragebogen fragt. Die Antworten
lauten durchgängig **Nein**:

- Gewalt, Schrecken, Sexualität, Nacktheit, Schimpfwörter — nein
- Drogen, Alkohol, Tabak — nein
- Glücksspiel, Simuliertes Glücksspiel — nein
- Nutzergenerierte Inhalte, Chat zwischen Nutzern — nein
  *(Das SSH-Terminal ist kein Chat: Es verbindet den Nutzer mit einem eigenen
  Server, nicht Nutzer untereinander.)*
- Standortweitergabe an Dritte — **nein**. Der Standort wird verarbeitet, aber
  nicht weitergegeben.
- Personenbezogene Daten teilen — nein
- Käufe digitaler Güter — nein

Erwartetes Ergebnis: **USK 0 / PEGI 3 / Everyone**.

---

## Zielgruppe

**Empfehlung: 18 und älter.**

Nicht weil die App etwas Bedenkliches enthält, sondern weil jede Altersstufe
unter 18 die App in Googles „Families"-Richtlinie zieht — mit zusätzlichen
Anforderungen an Werbung, Datenverarbeitung und einer strengeren Prüfung. Für
ein Fachwerkzeug ohne jede Zielgruppe unter 18 ist das Aufwand ohne Nutzen.

---

## Screenshots

Mindestens zwei sind Pflicht, acht sind möglich. Vorschlag in dieser
Reihenfolge — die ersten beiden sieht man in der Trefferliste:

1. **Dashboard** — zeigt in einem Bild, was die App umfasst
2. **Mobilfunk mit Messwerten** — das inhaltlich stärkste Bild
3. **WLAN-Kanaldiagramm** — grafisch am eingängigsten
4. **SSH-Terminal mit offener Sitzung**
5. **iperf3 mit Ergebnis**
6. **Subnetzrechner oder DNS-Ergebnis**

Aufnehmen direkt auf dem Telefon (Leiser + Ein/Aus). Achte darauf, dass keine
echten SSID-Namen, IP-Adressen aus deinem Firmennetz oder Servernamen sichtbar
sind — Screenshots im Play Store sind öffentlich und dauerhaft.

**Feature-Grafik (1024×500)** und **App-Symbol (512×512)** musst du gestalten.
Die Grafik erscheint oben auf der Store-Seite; ein schlichter Hintergrund mit
dem Namen genügt völlig.

---

## Datenschutz-URL

```
https://nettoolboxsupport-del.github.io/nettoolbox/PRIVACY
```

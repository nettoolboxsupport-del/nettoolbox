# Phase 2a – Werkzeuge ohne NDK, Teil 1

Umgesetzt: gemeinsame Werkzeug-Basis, **Subnetzrechner**, **Wake-on-LAN**, **Ping**.

Offen für 2b: DNS, Port-Scanner, IP-Scanner, HTTP/TLS-Inspector.

## Nicht verifiziert

Der Build wurde hier nicht ausgeführt. Abnahme:

```bash
.\gradlew.bat :app:assembleDebug test
```

## Entscheidungen

### Ping: Statistik selbst gerechnet, nicht geparst

`/system/bin/ping` gibt seinen Zusammenfassungsblock erst beim regulären Ende aus –
ein abgebrochener Lauf hätte also keine Statistik. Außerdem unterscheidet sich das
Format zwischen toybox, busybox und iputils. Die App zählt deshalb selbst mit.
`mdev` folgt iputils: Wurzel der mittleren quadratischen Abweichung.

### Paketverluste werden aus Sequenzlücken erkannt

`ping` schreibt für ein verlorenes Paket **gar nichts** in die Ausgabe. Der Runner
erkennt Verluste deshalb daran, dass eine Sequenznummer übersprungen wurde. Das
funktioniert für Verluste mitten im Lauf; ein Verlust am Ende wird erst bei der
Schlussrechnung sichtbar (`sent` aus der angeforderten Anzahl).

### Der Prozess wird über einen Completion-Handler beendet

`readLine()` blockiert bis zum nächsten Paket. Ohne
`invokeOnCompletion { process.destroy() }` liefe ein abgebrochener Endlos-Ping bis
zu ein Intervall weiter. Damit endet er sofort.

### TCP-Ping ist als solcher gekennzeichnet

`PingTransport.TCP_CONNECT` blendet im UI einen Hinweis ein, dass die Messung kein
ICMP ist. Der Wert misst den Handshake zu genau einem Port – ein geschlossener Port
antwortet schneller als ein offener, eine Firewall verändert das Ergebnis. Das als
"Ping" auszugeben wäre eine Falschaussage über das Netz.

`ICMP_DATAGRAM` fällt derzeit auf das System-Binary zurück, weil das native Modul
erst in Phase 5 existiert. Die Einstellung ist wählbar, aber sie liefert noch nicht,
was sie verspricht – das ist im Code markiert.

### Subnetzrechner ohne History

Der Rechner ist eine reine Funktion und rechnet bei jedem Tastendruck neu. Würde er
dabei Einträge in die Room-History schreiben, wäre die Historie der Werkzeuge, die
tatsächlich das Netz anfassen, nicht mehr lesbar. Ping und WOL schreiben History.

### Eigene IPv4-Arithmetik, geliehener IPv6-Parser

IPv4 läuft über `UInt` in einer Value Class – keine `InetAddress`, die auflösen
könnte, und die Bitarithmetik liegt offen. Bei IPv6 wäre ein handgeschriebener
Parser für `::`-Kompression, eingebettetes IPv4 und Zone-IDs eine Fehlerquelle;
dort wird `InetAddress` benutzt, aber erst nachdem geprüft wurde, dass die Eingabe
ein numerisches Literal ist. Damit kann kein Tippfehler zu einer DNS-Auflösung
werden.

Führende Nullen in Oktetten (`192.168.010.1`) werden abgelehnt: das ist in manchen
Resolvern oktal, und stillschweigend etwas anderes zu berechnen als der Nutzer
liest, ist schlimmer als eine Fehlermeldung.

### Zahlen werden in `Locale.ROOT` formatiert

Laufzeiten landen in Zwischenablage, Export und Fehlerberichten. Ein deutsches
Dezimalkomma würde jeden CSV-Konsumenten dahinter zerlegen.

## Was bewusst noch fehlt

- **Kein Export in Dateien.** `ResultActionBar` kann kopieren und teilen; der
  Export-Knopf braucht einen SAF-Picker und ein Format pro Werkzeug. Kommt in 2b
  zusammen mit den Scannern, die exportwürdige Datenmengen erzeugen.
- **Keine History-Ansicht.** `ToolRunRepository` schreibt Läufe, WOL liest sie
  bereits als "zuletzt verwendet". Ein History-Tab pro Werkzeug folgt in 2b.
- **Ping-Parameter nur teilweise im UI.** Paketgröße, TTL und DF-Bit sind im
  `PingRequest` und im Runner umgesetzt, aber noch nicht als Eingabefelder.

## Neue Tests

| Testklasse | deckt ab |
|---|---|
| `Ipv4SubnetTest` | /0 bis /32, /31 und /32 als Sonderfälle, Masken mit Löchern, führende Nullen, Splitting, Supernetting |
| `Ipv6SubnetTest` | /64-Berechnung, RFC-5952-Kompression, Ablehnung von Hostnamen und IPv4 |
| `PingOutputParserTest` | iputils-, busybox- und aufgelöste-Namen-Formate, `time<1 ms`, TTL Exceeded, Unreachable, Banner |
| `MagicPacketTest` | drei MAC-Schreibweisen, 102 Byte, Header, 16 Wiederholungen |

## Nächster Schritt

Phase 2b: DNS (dnsjava oder MiniDNS, dazu DoH und DoT), Port-Scanner, IP-Scanner
(ICMP-Sweep, TCP-Probe, mDNS über `NsdManager`, SSDP), HTTP/TLS-Inspector, dazu
Datei-Export über SAF und die History-Ansicht.

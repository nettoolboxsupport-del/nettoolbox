# Phase 2b – DNS

Umgesetzt: vollständiger DNS-Client mit eigenem Wire-Format-Codec, vier
Transporten und Parallelabfrage mehrerer Resolver.

Offen für 2c: Port-Scanner, IP-Scanner, HTTP/TLS-Inspector, SAF-Export,
History-Ansicht.

## Warum ein eigener Codec statt dnsjava oder MiniDNS

Abschnitt 3.6 der Spezifikation nennt dnsjava oder MiniDNS. Ich habe stattdessen
RFC 1035 selbst implementiert, aus drei Gründen:

1. Ich kann hier keine Bibliotheksversion und keine API verifizieren. Deine
   Spezifikation verbietet in Abschnitt 1 ausdrücklich, APIs zu raten.
2. Ein Codec bedient alle vier Transporte. UDP, TCP, DoT und DoH unterscheiden
   sich nur im Rahmen um dieselben Bytes.
3. Es ist byteweise testbar — `DnsMessageCodecTest` prüft Kompressionszeiger,
   jeden RDATA-Typ und die Zeigerschleife.

Wenn du lieber eine Bibliothek willst, ist der Austausch auf `DnsMessageCodec`
und `DnsTransports.kt` begrenzt. Sag Bescheid.

## Entscheidungen

**EDNS0 ist immer aktiv.** Jede Query enthält einen OPT-Record mit 1232 Byte
Payload-Größe — der Wert aus der DNS Flag Day 2020-Empfehlung: groß genug für
DNSSEC-Antworten, klein genug, um Fragmentierung auf Pfaden mit 1280 Byte MTU zu
vermeiden. Das DO-Bit ist gesetzt, sonst wäre das AD-Flag nicht aussagekräftig.

**Transaktions-ID wird geprüft.** Stimmt die ID der Antwort nicht mit der Anfrage
überein, wird die Antwort verworfen. Über UDP ist das das klassische
Spoofing-Signal, kein Schluckauf.

**TC-Flag löst automatisch TCP aus.** Eine abgeschnittene UDP-Antwort ist kein
Fehler, sondern die Aufforderung, dieselbe Frage über TCP zu stellen.

**DoT validiert das Zertifikat.** Der Socket wird mit dem Hostnamen erzeugt, damit
SNI gesendet und das Zertifikat dagegen geprüft wird. Ohne das würde DoT die
Anfrage zwar verschlüsseln, aber den Resolver nicht authentifizieren.

**DoH per POST, nicht GET.** Kein base64url, keine Cache-Interferenz durch
Zwischenstellen, und die Anfrage landet in keinem Access-Log.

**System-Resolver werden bei jedem Öffnen neu gelesen.** Sie kommen aus
`LinkProperties.dnsServers` der aktiven Verbindung — nicht aus dem seit Android 8
nutzlosen `net.dns1`. Ist privates DNS aktiv, weist der Screen darauf hin.

**Unbekannte Record-Typen werden als Hex angezeigt**, nicht verworfen. Ein
Resolver, der etwas zurückgibt, das die App nicht kennt, ist eine Information.

## Neue Tests

`DnsMessageCodecTest` — Header- und Namenskodierung, führender Punkt, leere
Labels, A/AAAA/MX/TXT/SRV-Dekodierung, Kompressionszeiger, NXDOMAIN, AD-Flag,
unbekannte Typen, zu kurze Nachricht, Zeigerschleife.

## Was fehlt

- **Kein Reverse-Lookup-Komfort.** PTR ist als Typ wählbar, aber die App baut
  `x.x.x.x.in-addr.arpa` noch nicht automatisch aus einer IP. Kleine Ergänzung,
  gehört zu 2c.
- **Keine Raw-Response-Ansicht im UI.** Die Hex-Daten sind pro Record vorhanden
  (`DnsRecord.rawData`), aber noch nicht darstellbar.
- **Kein `DnsResolver`-Vergleich (API 29+).** Die Spezifikation wünscht zusätzlich
  die Anzeige, was das Gerät selbst auflöst. Das ist ein separater Codepfad und
  kommt in 2c.

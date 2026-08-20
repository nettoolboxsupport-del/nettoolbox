# Phase 5a – Natives ICMP-Modul (Ping)

Umgesetzt: `:native:icmp` mit einem unprivilegierten ICMP-Ping-Socket
(SOCK_DGRAM + IPPROTO_ICMP), verdrahtet als echter `ICMP_DATAGRAM`-Transport
in `PingService`.

**Noch offen:** Traceroute (dasselbe Modul, TTL-Manipulation + IP_RECVERR),
`:native:iperf3`, Foreground Service für den iperf3-Server.

## Voraussetzung, bevor irgendetwas synct

**NDK und CMake fehlen bei dir** — das war in Phase 0 eine bewusste
Entscheidung, weil sie erst hier gebraucht werden. Ohne sie schlägt schon der
Gradle-**Sync** fehl, nicht erst der Build.

Android Studio → Settings → Languages & Frameworks → Android SDK → Tab
„SDK Tools" → anhaken:
- **NDK (Side by side)**
- **CMake**

Rund 1 GB Download. Danach normal syncen.

## Die wichtigste Einordnung: was hier ungeprüft ist

Ich kann hier keinen C-Compiler laufen lassen — dieser Code wurde nie
kompiliert, nie auf einem Kernel ausgeführt. Das ist eine andere
Vertrauensstufe als der Kotlin-Code bisher, wo zumindest der Compiler bei dir
jeden Fehler sofort gemeldet hat.

**Was ich mit hoher Zuversicht für richtig halte:**
- Die Struktur des ICMP-Pakets (Typ, Code, Prüfsumme, Identifier, Sequenznummer)
  und der Internet-Prüfsummenalgorithmus (RFC 1071) — das ist Lehrbuchstoff,
  identisch in praktisch jeder Ping-Referenzimplementierung.
- Die Socket-API-Aufrufe selbst (`socket`, `bind`, `sendto`, `recvfrom`,
  `poll`, `setsockopt`) — Standard-POSIX, in jedem NDK verfügbar.

**Was ich für richtig halte, aber nicht verifizieren konnte:**
- Dass `bind()` mit Port 0 auf einem Ping-Socket dem Kernel signalisiert,
  einen freien ICMP-Identifier zuzuweisen, und dass ankommende Antworten
  anhand dieses Identifiers wie bei UDP-Ports zugestellt werden.
- Dass `recvfrom()` auf einem Ping-Socket nur die ICMP-Nutzlast liefert, ohne
  IP-Header davor — anders als bei einem Raw-Socket.
- Dass der Kernel das Identifier-Feld beim Senden ohnehin überschreibt, was
  hier ausgenutzt wird (das Feld wird beim Aufbau des Pakets gar nicht erst
  gesetzt).

Das sind alles Aussagen über Linux-Kernel-Verhalten (`net/ipv4/ping.c`), die
ich aus Wissen über die Ping-Socket-Doku ableite, nicht aus einem Test hier.
**Der Ehrlichkeitsstatus ist: nach dokumentierter Semantik implementiert,
nicht durch einen physischen Test verifiziert.**

## Was im schlimmsten Fall passiert, wenn ich falsch liege

Nichts Dramatisches. Jeder native Aufruf läuft durch `IcmpNativeBridge`, die
jede Ausnahme und jeden `UnsatisfiedLinkError` abfängt. Schlägt etwas fehl:

- **`socket()`/`bind()` schlägt fehl** → `isAvailable()` gibt `false` zurück,
  `PingService` fällt automatisch auf das System-`ping` oder TCP-Timing
  zurück. Für dich unsichtbar, kein Absturz.
- **Der Socket öffnet, aber Antworten kommen nie an** (falls meine Annahme
  über das Zustellverhalten falsch ist) → jeder Ping-Versuch zeigt Timeout,
  bis die Statistik am Ende 100 % Verlust meldet. Kein Absturz, aber ein
  Ergebnis, das offensichtlich falsch aussieht — das wäre das Signal an mich,
  dass ich nachbessern muss.

Das ist der Grund, warum ich das Modul so geschnitten habe: der Fehlerfall ist
sichtbar und harmlos, nie ein Crash.

## Entscheidungen

### IPv4 only

ICMPv6 braucht `IPPROTO_ICMPV6` über `AF_INET6`, ein anderes Paketformat und
eine andere Prüfsumme (die die Pseudo-Header-Felder aus IPv6 einbezieht). Das
ist bewusst nicht in diesem Modul — eine IPv6-Zieladresse wird erkannt und an
den Aufrufer als „braucht das System-Ping" zurückgemeldet.

### Kein Traceroute in dieser Phase

Traceroute über Ping-Sockets bräuchte `IP_RECVERR` + `MSG_ERRQUEUE`, um
TIME_EXCEEDED-Antworten von Zwischen-Routern abzurufen — ein komplett anderer,
deutlich unsichererer Mechanismus als der normale Empfangspfad, den ich hier
verwendet habe. Das lieber als eigene, kleine Phase mit ausdrücklicher
Kennzeichnung der Unsicherheit, statt es hier mit reinzumischen und das
Vertrauen in den einfacheren Ping-Teil zu verwässern.

### TTL im Reply-Paket wird nicht angezeigt

`ttl` in `PingEvent.Reply` ist beim ICMP-Datagram-Transport immer `null`. Der
Grund steht oben: `recvfrom()` liefert vermutlich keinen IP-Header, also auch
keine TTL. Mit `IP_RECVTTL` und ausgewerteten Kontrolldaten (`cmsg`) ließe sich
das nachrüsten — bewusst nicht in dieser ersten Version, um die Änderungsfläche
klein zu halten.

### Kein `-Werror`

`CMakeLists.txt` nutzt `-Wall -Wextra` nur als Diagnose, nicht als Abbruch. Ein
Compiler-Warning, das ich nicht vorhergesehen habe, soll dir eine lesbare
Meldung zeigen, die du mir schicken kannst — nicht den ganzen Build blockieren.

### Migrations-Falle vermieden

Wer die App schon vor dieser Phase benutzt hat, könnte `TCP_CONNECT` als
`detectedPingMethod` gespeichert haben. Der ICMP-Verfügbarkeitscheck läuft
deshalb bei **jedem** Ping-Start live (er kostet nur zwei Syscalls, kein
Netzwerk) und hat Vorrang vor einem veralteten Cache-Eintrag — sonst bliebe
ein Gerät, das eigentlich echtes ICMP könnte, für immer auf TCP-Timing hängen.

## Warum ein eigenes Modul und keine Bibliothek

Für unprivilegierte ICMP-Sockets auf Android gibt es keine verbreitete,
vertrauenswürdige Bibliothek, die ich hier hätte prüfen können — das ist Nische
genug, dass Handschreiben tatsächlich der Standardweg ist (auch die
Referenzimplementierungen, die ich kenne, sind alle Eigenbau).

## Testen, sobald NDK/CMake installiert sind

1. Sync, dann `Make Project`.
2. Ping auf `1.1.1.1`. Unter dem Start-Knopf sollte jetzt **„ICMP über
   unprivilegierten Datagram-Socket"** stehen statt der System-Binary-Zeile —
   das zeigt, dass die Sondierung den neuen Transport gefunden hat.
3. Vergleiche die RTT-Werte grob mit denen aus Phase 2a (System-`ping`). Sie
   sollten in derselben Größenordnung liegen.
4. Falls die Zeile weiterhin „ICMP via System-ping" zeigt: entweder lehnt der
   Kernel deines Geräts Ping-Sockets ab (`ping_group_range` schließt die App
   nicht ein — durchaus üblich bei manchen Herstellern), oder etwas an meiner
   Kernel-Annahme stimmt nicht. Beides ist für mich interessant zu wissen.

## Nächster Schritt

Traceroute über dasselbe native Modul (TTL-Schleife + `IP_RECVERR`), danach
`:native:iperf3`.

# Phase 5b – Traceroute

Umgesetzt: TTL-Rampe über dasselbe native ICMP-Modul, als eigenständiges
Werkzeug mit Verlauf und Export.

**Noch offen:** `:native:iperf3`, Foreground Service für den iperf3-Server.

## Das ist der unsicherste Code im ganzen Projekt

Plain Ping (Phase 5a) liest nur den normalen `recvfrom()`-Pfad — dafür hattest
du bereits eine Bestätigung, dass er funktioniert. Traceroute braucht
zusätzlich einen zweiten, deutlich selteneren Mechanismus: die
**Socket-Fehlerwarteschlange** (`IP_RECVERR` + `recvmsg(MSG_ERRQUEUE)`), über
die der Kernel ICMP-Fehler wie „Time Exceeded" von Zwischen-Routern zustellt.
Ohne sie käme niemals eine Zwischenstation an, nur die Endstation oder gar
nichts.

Drei Structs/Konstanten (`IP_RECVERR`, `sock_extended_err`, `SO_EE_OFFENDER`)
kommen normalerweise aus `<linux/errqueue.h>`. Ich habe sie **von Hand
definiert statt eingebunden** — nicht aus Bequemlichkeit, sondern weil das
stabile Kernel-UAPI-Strukturen sind, die sich seit ihrer Einführung nie
geändert haben (jedes Linux-Netzwerktool verlässt sich darauf). Das
Selbstdefinieren ist hier tatsächlich das risikoärmere Vorgehen als zu hoffen,
dass der exakte Header-Pfad in dieser NDK-Version existiert.

**Der Ehrlichkeitsstatus:** nach dokumentiertem Kernel-Verhalten implementiert,
nie an einem echten Gerät geprüft. Das ist eine Stufe unsicherer als Phase 5a.

## Woran du erkennst, ob es funktioniert

Probier `traceroute` auf `1.1.1.1`. Ein plausibles Ergebnis:
- Mehrere Zeilen mit unterschiedlichen IP-Adressen (dein Router, dann Hops
  deines Providers), Laufzeiten, die pro Hop tendenziell steigen.
- Die letzte Zeile trägt „Ziel erreicht" und eine IP nahe an `1.1.1.1`.
- Einzelne Hops mit `*` sind normal — manche Router antworten nicht auf
  abgelaufene TTLs, das ist kein Fehler.

**Wenn es nicht funktioniert**, sieht das vermutlich so aus: **jeder** Hop
zeigt `*`, bis entweder die Hop-Obergrenze (30) erreicht ist oder die
Endstation zufällig doch über den normalen Echo-Antwort-Pfad durchkommt (der
ja bereits erwiesenermaßen funktioniert) — dann siehst du nur eine einzige
Zeile mit „Ziel erreicht" und sonst nichts. Das wäre das Signal, dass
`IP_RECVERR` bei dir nicht wie erwartet zugestellt wird. Schick mir in dem
Fall einfach den Screenshot, das ist für mich diagnostisch wertvoll.

## Entscheidungen

### Ein Socket, eine Sequenznummer pro Hop

Jeder Hop bekommt `sequence == ttl` (1 bis 30, eindeutig über den ganzen
Lauf). Eine verspätete Antwort eines früheren Hops kann so nie mit der
aktuellen verwechselt werden — sie wird beim Sequenzabgleich verworfen und
bleibt für ihren ursprünglichen Hop unsichtbar. Das ist eine bewusste
Vereinfachung: einfache Traceroute-Implementierungen verhalten sich genauso.

### DEST_UNREACHABLE beendet den Lauf

Antwortet ein Zwischenknoten mit „nicht erreichbar", ist klar, dass keine
weitere Erhöhung der TTL etwas ändert. Der Lauf stoppt dort, statt die
restlichen Hops mit Sicherheit auf Timeout laufen zu lassen.

### Kein eigener Fallback-Pfad für Traceroute

Anders als Ping hat Traceroute keine Ausweichmethode (kein „TCP-Traceroute"
o.ä. in dieser Phase). Steht der native Ping-Socket nicht zur Verfügung, zeigt
das Werkzeug den bekannten `NATIVE_UNAVAILABLE`-Fehler mit Empfehlung, es
über die Ping-Methodenerkennung zu prüfen.

## Nächster Schritt

`:native:iperf3` über JNI-Anbindung an libiperf, danach der Foreground Service
für den Server-Modus.

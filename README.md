# NetToolbox

Ein Diagnosewerkzeug für Netzwerktechniker — Android, quelloffen, ohne
Telemetrie.

Mobilfunk- und WLAN-Analyse, Ping, Traceroute, DNS, Portscanner, IP-Scanner,
HTTP/TLS-Inspektor, Subnetzrechner, Wake-on-LAN, Durchsatzmessung mit echtem
iperf3 und ein SSH-Terminal mit VT/xterm-Emulation.

**Alle Messdaten bleiben auf dem Gerät.** Kein Konto, kein Server, keine
Analyse-Bibliotheken. Ein Export findet nur statt, wenn der Nutzer ihn auslöst
und das Ziel selbst wählt. Siehe [PRIVACY.md](PRIVACY.md).

## Was drin ist

| Bereich | Umfang |
|---|---|
| **Mobilfunk** | Bedienzelle, Nachbarzellen, Signalmetriken je Funktechnik, ARFCN-Bandzuordnung, 5G-NSA-Erkennung, Drive-Test-Aufzeichnung mit Positionsbezug |
| **WLAN** | Netzsuche, Kanaldiagramm, Signalverlauf, Beobachtungsliste, Verbindungsdetails |
| **Werkzeuge** | Ping (System, ICMP-Datagramm, TCP), Traceroute, DNS (A–CAA, mehrere Resolver, DoH/DoT), Portscanner, IP-Scanner (TCP, mDNS, SSDP, NetBIOS), HTTP/TLS-Inspektor, Subnetzrechner, Wake-on-LAN |
| **Durchsatz** | iperf3 als Client und als Server, echtes libiperf, kein Eigenbau |
| **SSH** | Terminal mit libvterm, Host-Key-Prüfung ohne Umgehungsmöglichkeit, Verbindungsprofile |
| **Karte** | MapLibre, OpenCelliD-Import, Messfahrten auf der Karte |

## Bauen

```
git clone --recurse-submodules <url>
cd nettoolbox
./gradlew :app:assembleDebug
```

Ohne `--recurse-submodules` fehlen die Quelltexte von iperf3 und libvterm.
Nachträglich:

```
git submodule update --init --recursive
```

Voraussetzungen: JDK 17 oder neuer, Android SDK mit NDK 27 und CMake 3.22.
Ein Gradle-Wrapper liegt bei, eine eigene Gradle-Installation ist nicht nötig.

## Technischer Zuschnitt

Kotlin, Jetpack Compose, Material 3. Fünfzehn Gradle-Module: `:app`, fünf
`:core`-Module, sechs `:feature`-Module und drei `:native`-Module mit C-Code
über JNI. Hilt für Abhängigkeiten, Room und DataStore für Persistenz,
kotlinx.serialization, MapLibre statt Google Maps.

minSdk 28, targetSdk 36.

Drei native Bibliotheken: unprivilegiertes ICMP für Ping und Traceroute,
vendored iperf3 und vendored libvterm. Alle mit 16-KB-Seitenausrichtung, wie
sie der Play Store seit November 2025 verlangt.

## Entscheidungen und Fallstricke

Die Dateien `PHASE*_NOTES.md` dokumentieren den Weg — nicht als Chronik,
sondern als Begründungen. Warum libvterm statt Termux (und damit Apache 2.0
statt GPLv3), warum iperf3 ohne Autotools gebaut wird, wo `exit()` in einer
Bibliothek steckt, warum `ACCESS_BACKGROUND_LOCATION` nicht angefordert wird.

Und die Fehler mit Ursache: ein stiller `runCatching` um einen Netzwerkaufruf
im Hauptthread, der einen SSH-Paketstrom zerschoss; ein Widget, das nie
aktualisieren konnte, weil Glance die Komposition am Leben hält statt
`provideGlance` erneut aufzurufen. Beides sind Fehlertypen, die
wiederkommen — deshalb stehen sie aufgeschrieben statt stillschweigend
behoben.

## Lizenz

Apache License 2.0, siehe [LICENSE](LICENSE).

Fremdkomponenten und ihre Lizenzen: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
Jede davon wurde in der `LICENSE`-Datei des jeweiligen Projekts nachgelesen.

Kartendaten © OpenStreetMap-Mitwirkende (ODbL), Zelldaten © OpenCelliD
(CC-BY-SA). Beide Nennungen sind lizenzrechtlich vorgeschrieben und erscheinen
in der App auf der Karte und im Über-Bildschirm.

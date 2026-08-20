# Phase 5c – iperf3

Umgesetzt: echtes iperf3 als Git-Submodul, nativer Build ohne Autotools,
JNI-Bindung, Client-Modus (**auf deinem Gerät bestätigt**) und Server-Modus
mit Foreground Service.

## Der wichtigste Fund: `exit()` in einer Bibliothek

iperf3 ist als CLI-Programm geschrieben. An mehreren Stellen ruft es direkt
`exit()` auf — in einer Android-App würde das **den gesamten Prozess
beenden**, ohne Absturzdialog, ohne Log, einfach weg.

Ich habe alle Vorkommen in den kompilierten Dateien durchgesehen und auf
Erreichbarkeit geprüft:

| Ort | Kontext | Erreichbar? |
|---|---|---|
| `iperf_api.c:1288` | `-v`-Flag im CLI-Argumentparser | **Nein** — `iperf_parse_arguments()` wird nie aufgerufen |
| `iperf_api.c:1810/1814` | `-h` und unbekannte Flags, ebenfalls Parser | **Nein** — dito |
| `iperf_api.c:5363/5365` | `iperf_got_sigend()`, Signal-Handler | **Nein** — `iperf_catch_sigend()` wird nie registriert |
| `iperf_api.c:5404` | PID-Datei, „andere Instanz läuft" | **Nein** — nie konfiguriert |
| `iperf_server_api.c:709` | One-Off-Server mit Idle-Timeout | **Vermeidbar — und vermieden** |
| `iperf_util.c:68/75` | `readentropy()` liest `/dev/urandom` | **Ja, aber praktisch nie** |

Die beiden relevanten:

**`iperf_server_api.c:709`** feuert, wenn ein One-Off-Server sein Idle-Timeout
ohne Verbindung erreicht. Der Zweig hängt an `iperf_get_test_one_off()`. Ich
setze `one_off` deshalb **gar nicht** — und brauche es auch nicht:
`iperf_run_server()` kehrt ohnehin nach jedem abgeschlossenen Test zurück, das
Flag steuert nur, ob iperf3s eigene CLI-Schleife abbricht. Zweiter Riegel:
`idle_timeout` wird ebenfalls nie gesetzt.

**`readentropy()`** stirbt, wenn `/dev/urandom` nicht lesbar ist. Unter Android
ist die Datei immer lesbar, und dein funktionierender Client-Test bestätigt es —
aber es ist eine dokumentierte Restgefahr, kein ausgeschlossenes Risiko.

## Warum kein Autotools

iperf3 baut normalerweise mit `./configure && make`. Das ist ein Shell-Skript,
und ein von Gradle angestoßener CMake-Schritt kann sich unter Windows nicht
darauf verlassen, eine POSIX-Shell zu finden.

Stattdessen: die Quelldateien direkt als CMake-Ziel, mit einer **von Hand
geschriebenen `iperf_config.h`**, abgeleitet aus der echten Vorlage
`iperf_config.h.in`. Jedes Flag ist dort einzeln kommentiert — und wo ich mir
nicht sicher war, steht es auf *aus* statt geraten auf *an*. Ein falsches „an"
bricht den Build, ein falsches „aus" kostet nur ein Feature, das die App
ohnehin nicht anbietet.

**SCTP und OpenSSL-Auth sind ganz draußen**, sowohl per Flag als auch durch
Weglassen der Dateien. Ich habe vorher nachgesehen, dass jede Referenz auf
deren Symbole in `iperf_api.c` / `iperf_client_api.c` / `iperf_server_api.c`
bereits hinter `#if defined(HAVE_SCTP_H)` bzw. `#if defined(HAVE_SSL)` liegt.

## Server-Modus: die Stop-Schwierigkeit

`iperf_run_server()` ist eine interne `select()`-Schleife. libiperf hat
**keinen öffentlichen Aufruf, um einen laufenden Server abzubrechen** — die CLI
löst das über Signale und `longjmp`, was über JNI-Frames hinweg undefiniertes
Verhalten wäre.

Der Aufbau hier:

- Die Schleife liegt in Kotlin, nicht in C. `iperf_run_server()` kehrt nach
  jedem abgeschlossenen Test zurück; dazwischen prüft Kotlin ein Stop-Flag.
- **`iperf_reset_test()` zwischen zwei Läufen** — das hätte ich ohne den Blick
  in `main.c` übersehen; ohne den Aufruf startet der nächste Lauf auf den
  Resten des vorigen.
- Upstream ruft bei `rc < -1` selbst `iperf_errexit()` auf. Das übernehme ich
  **nicht** — der Code wandert stattdessen nach Kotlin.

Bleibt: während der Server auf einen Client wartet, steckt er im `select()`.
`stop()` setzt daher das Flag **und** öffnet kurz eine TCP-Verbindung zum
eigenen Port, um das `select()` aufzuwecken.

**Das ist ein Workaround, kein dokumentierter Mechanismus.** Er nutzt nur
normales Socket-Verhalten und fasst keine libiperf-Interna an.

### Nachtrag: was der Test auf echter Hardware gezeigt hat

Stop **während eines laufenden Tests** gedrückt: Die App ging in den
Ruhezustand, aber der Client auf dem Notebook lief seine vollen zehn Sekunden
sauber zu Ende.

Das ist die Ursache: Während ein Test läuft, schließt iperf3 seinen
Listening-Socket (`iperf_server_api.c`, Zeilen 893–900). Die Weck-Verbindung
lief also ins Leere — folgenlos. Der Test endete regulär,
`iperf_run_server()` kehrte zurück, die Schleife sah das Flag und stieg aus.

**Das Verhalten ist richtig.** Einen laufenden Test abzuschneiden würde dem
Client ein kaputtes Ergebnis liefern, und libiperf bietet ohnehin keine
Möglichkeit dazu.

**Falsch war die Anzeige.** Die App meldete sofort „beendet", während nativ
noch zehn Sekunden gemessen wurde — ein Zustand, der nicht stimmte. Behoben
durch:

- Einen eigenen `isStopping`-Zustand. „Beendet" erscheint erst, wenn
  `iperf_run_server()` tatsächlich zurückgekehrt ist.
- **Kein `cancel()` mehr auf den Job.** Das war doppelt sinnlos: Cooperative
  Cancellation kann einen nativen Aufruf nicht unterbrechen, die Messung lief
  ohnehin weiter — nur das UI behauptete etwas anderes.
- Der Startknopf ist während `isStopping` gesperrt, sonst würde ein schneller
  Neustart auf einen Port treffen, den der alte Server noch hält.

Der Weckmechanismus für den **wartenden** Server (kein Test aktiv) ist damit
weiterhin ungeprüft — in dem Fall ist der Listening-Socket offen und die
Verbindung sollte greifen.

## Modulzuschnitt korrigiert

Ich hatte iperf3 zuerst in `:feature:tools` gebaut — aus Gewohnheit, obwohl
`:feature:iperf` seit Phase 0 existiert und die Dashboard-Kachel dorthin zeigt.
Beim Aufräumen kam heraus, dass `ToolRunRepository` in `:feature:tools` lag und
damit für `:feature:iperf` unerreichbar war, ohne eine Abhängigkeit zwischen
zwei Feature-Modulen einzuführen.

Es liegt jetzt in **`:core:database`** — dort hängt es nur am `ToolRunDao` und
ist für jedes Feature-Modul erreichbar. Neun Importe wurden umgestellt.

## Bestätigt auf echter Hardware

**Client:** funktioniert (getestet über Port 80, weil die Notebook-Firewall
5201 eingehend blockt).

**Server:** funktioniert. Notebook (`192.168.41.100`, iperf3 **3.17**) gegen
das Telefon als Server (`192.168.41.153`, vendored **3.21+**):

```
[  5]   0.00-10.00  sec   595 MBytes   499 Mbits/sec    sender
[  5]   0.00-10.02  sec   592 MBytes   496 Mbits/sec    receiver
```

Damit sind belegt: der CMake-Build ohne Autotools, die handgeschriebene
`iperf_config.h`, die JNI-Bindung, `iperf_run_server()`, der Foreground
Service, das Binden auf Port 5201 — und nebenbei die
**Protokollkompatibilität zwischen iperf3 3.17 und 3.21+**, was die
Entscheidung für echtes iperf3 statt eines Eigenbaus rechtfertigt: die App
spricht mit den Servern, die im Feld ohnehin laufen.

Rund 500 Mbit/s über WLAN heißt außerdem, dass der JNI-Weg kein Flaschenhals
ist.

## Zum Testen

Für den Server:

1. Server-Modus in der App, Port 5201, starten.
2. Die App zeigt die eigenen IP-Adressen an — das ist die Adresse für den
   Client.
3. Vom Notebook: `iperf3 -c <Adresse> -p 5201`

Auf dem Weg **zum Telefon** ist die Notebook-Firewall nicht beteiligt, das
sollte also ohne Regel funktionieren.

### JSON-Auswertung: bestätigt

Die als unsicher markierten Feldnamen **stimmen**. Nach einem Serverlauf zeigt
die App echte Mbit/s-Werte, nicht den Ersatzhinweis — `end.sum_sent` und
`end.sum_received` liegen bei dieser iperf3-Version also dort, wo der Parser
sie sucht. Die Best-Effort-Behandlung bleibt trotzdem drin: Sie kostet nichts
und trägt über einen Versionswechsel des Submoduls hinweg.

### Der Umweg dorthin: ein UI-Fehler, der wie ein Datenfehler aussah

Zuerst zeigte die Server-Ansicht nach einem Testlauf gar nichts. Der Verdacht
lag auf dem nativen Rückweg — falsch. Drei Kandidaten habe ich im Quellcode
geprüft und alle entlastet:

- `iperf_reset_test()` löscht `json_output_string` **nicht** (das tut nur
  `iperf_free_test()`), die Aufrufreihenfolge in `nativeRunServerOnce` war also
  in Ordnung.
- Der Server ruft `iperf_json_finish()` sehr wohl auf
  (`iperf_server_api.c:996`).
- `iperf_run_server()` kehrt nach jedem Test zurück: die Schleife läuft bis
  `IPERF_DONE`, dann `return 0`.

Die Ursache lag im UI: Ich rendere pro Test eine Karte, die nur die Zahlen
enthielt — und **leer blieb**, wenn diese null waren. Beim Client gab es dafür
einen Ersatzhinweis, beim Server hatte ich ihn vergessen. Ein Test ohne
lesbare Werte sah damit exakt aus wie „nichts passiert".

Jede Karte zeigt jetzt immer etwas: Uhrzeit, die Werte falls vorhanden, sonst
einen von **zwei unterschiedenen** Hinweisen — „kein JSON zurückgegeben"
(nativer Fehler) gegen „Felder nicht lesbar" samt Roh-JSON-Auszug
(Parser-Fehler). Der Unterschied ist diagnostisch entscheidend und war vorher
nicht sichtbar.

**Lehre daraus:** Eine Ansicht, die bei fehlenden Daten nichts rendert, ist
nicht neutral — sie behauptet, es sei nichts geschehen. Das gilt für jede
Ergebnisliste in dieser App.

## Was noch offen ist

Aus Abschnitt 4.3 fehlen beim iperf3-Client noch: parallele Streams als
Eingabefeld, bidirektionaler Modus, MSS/Window, Omit sowie das
Live-Durchsatzdiagramm während des Laufs. Der Unterbau (`Iperf3ClientRequest`)
trägt die Felder bereits.

Damit ist **Phase 5 abgeschlossen** — ICMP-Ping, Traceroute und iperf3 stehen.
Als Nächstes stünde Phase 6 an: SSH-Terminal mit der Termux-Emulation, womit
die App unter GPLv3 fällt (so entschieden in `PHASE2C_NOTES.md`).

### Nachtrag 2: warum der Weckruf nie ankam

Stop bei **wartendem** Server (kein Test aktiv) blieb dauerhaft in „Wird
beendet…" hängen, der Startknopf war danach tot. Genau der Fall, den ich oben
als ungeprüft markiert hatte.

Die Ursache lag nicht bei iperf3, sondern bei mir: `runner.stop()` wird aus
`onStartCommand()` gerufen — **Hauptthread**. Android wirft dort für jede
Socket-Operation eine `NetworkOnMainThreadException`. Mein `runCatching`
drumherum hat sie kommentarlos geschluckt. Die Weck-Verbindung wurde also nie
aufgebaut, `select()` blieb stehen, `iperf_run_server()` kehrte nicht zurück.

Ein stiller `runCatching` um fremden Code ist genau deshalb gefährlich: Er
macht aus einem Absturz ein Schweigen. Behoben durch einen eigenen Scope im
Runner, der den Verbindungsversuch auf dem IO-Dispatcher ausführt.

### Und ein Konstruktionsfehler dahinter

Der eigentliche Mangel war nicht der Thread, sondern dass ein Fehlschlag der
Weck-Verbindung überhaupt in eine **Sackgasse** führen konnte. Der Knopf war
an `isStopping` gekoppelt, und `isStopping` wurde ausschließlich vom
Zurückkehren des nativen Aufrufs zurückgesetzt. Kam der nie zurück, kam auch
das UI nie zurück — nur „App zwangsbeenden" half.

Es gibt jetzt einen Wachhund: kommt libiperf binnen 60 Sekunden nicht zurück,
gibt der Service die Anzeige trotzdem frei und schreibt dazu, **was wirklich
gilt** — dass der native Server seinen Port möglicherweise noch hält und ein
Neustart daran scheitern kann. Die Zeitspanne ist bewusst großzügig: Ein
Client bestimmt seine Testdauer selbst, und ein langer, völlig normaler Test
darf nicht als Hänger missverstanden werden.

**Lehre:** Ein Zustandsautomat, dessen Ausgang allein an einem fremden,
blockierenden Aufruf hängt, braucht immer einen zweiten Weg heraus.

### Nachtrag 3: Ergebnisse überlebten das Beenden nicht

Beim Beenden des Servers verschwand auch die Liste der abgeschlossenen Tests.
Ursache: `finishService()` ruft `controller.reset()`, und das baute einen
komplett frischen `Iperf3ServerState` — inklusive leerer `completedTests`.

Das war ein Denkfehler in der Zuordnung. Der Lauf-Zustand (lauscht, Port,
wird beendet) gehört dem Server. Die Messergebnisse gehören **dem Benutzer**.
Dass ein Listening-Socket zugeht, sagt über bereits gemessene Werte nichts
aus — sie deshalb wegzuwerfen ist Datenverlust, nicht Aufräumen.

`reset()` erhält die Ergebnisse jetzt und setzt nur den Lauf-Zustand zurück.
Damit überleben sie auch einen Neustart des Servers, weshalb es einen
ausdrücklichen **„Löschen"**-Knopf über der Liste gibt: Wenn Daten nicht mehr
von selbst verschwinden, muss es einen sichtbaren Weg geben, sie loszuwerden.

**Lehre:** Beim Zurücksetzen eines Zustands immer trennen, wem welches Feld
gehört. Ein pauschales „alles auf Anfang" löscht zuverlässig auch das, was
nicht dem Vorgang gehört.

### Nachtrag 4: kaputte Zeichen — durch meine eigenen Edits

Im Knopf stand „Wird beendetâ€¦" statt „Wird beendet…". Ursache war keine
Schriftart und kein Android-Problem, sondern **mein Werkzeuggebrauch**.

Ich habe `strings.xml` mit `perl -0pi -e` bearbeitet. Sobald der
Ersetzungstext ein Nicht-ASCII-Zeichen enthält, stuft Perl den gesamten
String auf Zeichen-Semantik hoch — und schreibt beim Speichern die bereits
vorhandenen UTF-8-**Bytes** so heraus, als wären es Latin-1-Zeichen. Jedes
davon wird erneut UTF-8-kodiert. Aus `E2 80 A6` („…") wird
`C3 A2 C2 80 C2 A6`. Perl warnt dabei nur beiläufig mit „Wide character in
print"; ich hatte die Warnung gesehen und nicht ernst genommen.

Zwei Prüfungen haben versagt, und beide auf lehrreiche Weise:

1. **`iconv -f UTF-8 -t UTF-8`** ging durch. Doppelt kodiertes UTF-8 ist
   selbst wieder gültiges UTF-8 — die Prüfung konnte den Fehler prinzipiell
   nicht finden.
2. Mein erster Reparaturlauf suchte nach `C3 82`/`C3 83`, den Signaturen
   doppelt kodierter **Umlaute**. Die Zeilen mit `…` tragen aber `C2 80`/
   `C2 A6` und blieben deshalb stehen. Ich hielt die Sache für erledigt,
   während drei Zeilen noch kaputt waren.

Das tragfähige Kriterium: eine Zeile eine UTF-8-Ebene zurückdekodieren; liegen
danach **alle** Zeichen unter U+0100 und mindestens eines im Bereich
U+0080–U+009F, ist es Doppelkodierung. Dieser Block sind C1-Steuerzeichen, die
in echtem Text nie auftauchen. Damit repo-weit geprüft: nur diese eine Datei
war betroffen, alle Zeilen sind repariert.

**Lehre:** Für Dateien mit Nicht-ASCII-Inhalt kein `perl -0pi` ohne
ausdrückliche `:raw`- bzw. `:encoding(UTF-8)`-Ebenen. Und eine
Gültigkeitsprüfung ist keine Richtigkeitsprüfung — „ist gültiges UTF-8" sagt
nichts darüber, ob es das *gemeinte* UTF-8 ist.

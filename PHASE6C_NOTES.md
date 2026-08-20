# Phase 6c – Terminal-Oberfläche & Integration

Dritter und abschließender Teil des SSH-Terminals: Die Compose-Oberfläche,
das Single-Canvas-Rendering, die Ein-/Ausgabeschleife, die Modifier-Tastaturleiste,
die Host-Key-Dialoge und die Einbindung in das `:app`-Modul (Dashboard & Navigation).

---

## 1. Single-Canvas-Renderer (`TerminalCanvas.kt`)

Entsprechend den nicht verhandelbaren Performanz-Regeln wurde das Zeichengitter auf
einem einzigen `androidx.compose.foundation.Canvas` implementiert:

* **Zero-Allocation-Indexrechnung**: Der native Puffer wird in einem einzigen
  JNI-Aufruf (`VtermBridge.snapshot`) in ein wiederverwendbares `IntArray` kopiert
  (4 `Int`s pro Zelle). `VtermCell` extrahiert Codepoints, RGB-Farben und Attribute
  über direkte Indexarithmetik ohne Objektallokationen pro Frame.
* **Monospace-Glyphen-Rendering**: Text wird direkt auf dem nativen Canvas
  gezeichnet. Schriftart `Typeface.MONOSPACE` mit exakt gemessener Glyphengeometrie
  (`charWidth`, `charHeight`, `baselineOffset`).
* **Attribute & Farben**: Volle Unterstützung für Vorder-/Hintergrundfarben (24-Bit RGB
  oder Default-Theme), `ATTR_BOLD`, `ATTR_ITALIC`, `ATTR_REVERSE`, `ATTR_UNDERLINE`,
  `ATTR_STRIKE` und doppelbreite Zeichen (`width == 2`).
* **Cursor**: Animierter/blinkender Cursor mit Koordinaten direkt aus `VtermBridge.cursor`.
* **Dynamische Skalierung**: Bei Größenänderungen (z. B. Bildschirmdrehung oder
  Tastatureinblendung) werden `rows` und `cols` live neu berechnet und über
  `VtermBridge.setSize` sowie `SshShellSession.resize` an Server und Emulator übermittelt.

---

## 2. Eingabe & Modifier-Zusatzleiste (`ModifierBar.kt`)

* **IME- & Hardware-Tastatureingabe**: Transparente Eingabebehandlung fängt
  sowohl Software-Tastaturen (Gboard etc.) als auch physische Tastaturen (USB/Bluetooth)
  ab und leitet Zeichen an `VtermBridge.keyUnichar` bzw. `VtermBridge.keyKey` weiter.
* **Zusatzleiste**:
  - Latching/Sticky-Tasten für `CTRL` und `ALT` (Tippen aktiviert den Modifikator
    für den nächsten Tastendruck).
  - Direkt-Buttons für `ESC`, `TAB`, Richtungstasten (`▲`, `▼`, `◀`, `▶`).
  - Schnelltasten für Terminal-Kombinationen (`Ctrl+C`, `Ctrl+D`, `/`, `-`, `|`, `~`).
  - Dropdown-Menü für Funktionstasten (`F1`–`F12`, `Home`, `End`, `PageUp`, `PageDown`, `Insert`, `Delete`).

---

## 3. Host-Key-Dialoge (`HostKeyDialog.kt`)

Gemäß Spezifikation und Entwurfsentscheidung (H):
* **Unknown (Erstkontakt)**:
  - Sachlicher Dialog zur Verifikation vor dem ersten Verbindungsaufbau.
  - Zeigt Host, Port, Schlüsseltyp und SHA-256-Fingerprint im OpenSSH-Format.
* **Changed (Möglicher Man-in-the-Middle-Angriff)**:
  - Sicherheitswarnung mit roter Signalfarbe.
  - Gegenüberstellung des bisher gespeicherten Schlüssels vs. neu präsentierten Schlüssels.
  - Standard-Aktion ist „Abbrechen".

---

## 4. Verbindungsprofile & App-Integration

* **SshProfileStore**: Speichern und Verwalten von Verbindungsprofilen in DataStore.
* **Dashboard & Navigation**:
  - Vektor-Icon `ic_terminal.xml` und Registrierung in `NetToolboxIcons.Terminal`.
  - Kachel „SSH-Terminal" im Dashboard.
  - Typisierte Navigationsroute `SshRoute` in `NetToolboxNavHost`.
  - Vollständige Lokalisierung in Deutsch (`values-de`) und Englisch (`values`).

## Der Fehler, der die Sitzung beim ersten Tastendruck tötete

Symptom: Verbindung und Anmeldung liefen sauber, das Ubuntu-Willkommen kam an,
und beim ersten Tastendruck war die Sitzung weg. Die App meldete jeden
weiteren Tastendruck als erfolgreich gesendet.

### Was es nicht war

Vier Hypothesen, alle mit Belegen widerlegt statt weggeschoben:

| Verdacht | Widerlegt durch |
|---|---|
| Falsches Byte für Enter | libvterms `keycodes`-Tabelle: `ENTER` ist `'\r'`; das Log zeigte `0D` |
| Schreib-Coroutine hungert hinter dem blockierenden `read()` | `IoDispatcher` ist `Dispatchers.IO` mit 64 Threads, nicht single-threaded |
| `getOutputStream()` vor `connect()` geholt | jsch-Quelltext: der Strom initialisiert lazy beim ersten `write()` |
| AES-GCM auf Androids JCE | Test mit erzwungenem `aes256-ctr` scheiterte identisch |
| Emulator-NAT | Auf echtem Gerät identisches Verhalten |

Jede dieser Runden hat Zeit gekostet. Der Grund war jedes Mal derselbe: Ich
habe aus einem Symptom auf eine Ursache geschlossen, statt die Ursache
messbar zu machen.

### Was die Sache gelöst hat

Ein OpenSSH im Vordergrund auf einem Testport:

```
sudo /usr/sbin/sshd -ddd -p 2222
```

Und dort stand es im Klartext:

```
ssh_dispatch_run_fatal: Connection from user wireguard 192.168.41.153
    port 33256: message authentication code incorrect
```

Der Server verwirft ein Paket der App wegen falscher Integritätsprüfung und
kappt die TCP-Verbindung, **ohne** `SSH_MSG_DISCONNECT`. Clientseitig kommt
davon nur ein nacktes EOF an — deshalb war der Fehler aus der App heraus
prinzipiell nicht zu sehen.

### Die Ursache

`SshShellSession.resize()` war ein blankes `runCatching` um
`channel.setPtySize()`. Aufgerufen wurde es aus Composes `onSizeChanged`,
also im **Hauptthread** — und zwar genau dann, wenn der Terminal-Bildschirm
erstmals vermessen wird oder die Bildschirmtastatur aufgeht.

Android wirft dort `NetworkOnMainThreadException`. Entscheidend ist, **wo**
sie geworfen wird, nämlich mitten in jschs eigenem Sendepfad:

```java
synchronized (lock) {
  encode(packet);   // verschlüsselt und MACt mit der laufenden Sequenznummer
  io.put(packet);   // <- wirft hier, mitten im Schreiben auf den Socket
  ++seqo;           // <- wird nie erreicht
}
```

Ergebnis: ein **halbes Paket** im Strom und eine Sequenznummer, die nicht
weitergezählt hat. Ab da ist jedes Paket verschoben, der Server rechnet den
MAC über die falschen Bytes, und die nächste echte Übertragung fliegt raus.
Die Ausnahme selbst hat das `runCatching` verschluckt — unsichtbar.

Behoben durch:

- `resize()` läuft auf dem IO-Dispatcher und **unter demselben Lock** wie das
  Schreiben von Nutzdaten. Jeder Zugriff auf den Kanal geht jetzt durch genau
  einen serialisierten Pfad.
- Fehlschläge werden geloggt statt verschluckt, und `resize()` gibt zurück, ob
  es geklappt hat.
- `disconnectSession()` ebenfalls vom Hauptthread heruntergeholt:
  `channel.disconnect()` sendet ebenso Pakete und stand in derselben Falle.

### Lehren

**Zum dritten Mal in diesem Projekt derselbe Fehlertyp:** ein stilles
`runCatching` um fremden Code, der im Hauptthread Netzwerkarbeit macht. Beim
iperf3-Server war es der Weckruf-Socket, hier die PTY-Größenänderung. Die
Regel daraus ist keine Stilfrage mehr: **`runCatching` um fremden Code muss
den Fehler protokollieren oder zurückgeben — nie beides unterlassen.**

**Ein abgebrochener Schreibvorgang ist schlimmer als gar keiner.** Bei einem
zustandsbehafteten Protokoll hinterlässt eine Ausnahme mitten im Senden nicht
"nichts passiert", sondern einen beschädigten Strom. Alle folgenden
Operationen scheitern dann an einer Stelle, die mit der Ursache nichts zu tun
hat.

**Symptomort und Fehlerort lagen zehn Sekunden und einen Thread auseinander.**
Der Absturz zeigte sich beim Tastendruck; verursacht wurde er beim Layout des
Bildschirms. Deshalb führte jede Suche entlang des Tastaturpfads ins Leere.

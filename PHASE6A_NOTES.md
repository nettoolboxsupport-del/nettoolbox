# Phase 6a – Terminal-Emulation (nativ)

Erster Teil des SSH-Terminals: das Modul `:native:vterm`. Es macht aus einem
Byte-Strom ein Zeichengitter — mehr nicht. SSH-Transport und Darstellung sind
getrennte Schritte (6b und 6c).

## Die Lizenzentscheidung, und was sie tatsächlich gekostet hat

Ursprünglich war der Termux-Weg beschlossen: erprobt, aber **GPLv3**, womit
NetToolbox als Ganzes darunter gefallen wäre. Bei der Aufschlüsselung stellte
sich heraus, dass nur **einer von drei** Bausteinen überhaupt ein
Lizenzproblem hat:

| Baustein | Lizenzproblem? |
|---|---|
| SSH-Protokoll | Nein — permissive Bibliotheken vorhanden |
| Terminal-Emulation | **Ja** — hier saß Termux |
| Darstellung/Eingabe | Nein — schreiben wir ohnehin selbst |

Entscheidend war eine Beobachtung zum Zuschnitt: Termux' Emulator ist gebaut,
um **lokale Prozesse** zu beherbergen — pty allozieren, fork/exec, Signale,
Job Control. Ein SSH-Client braucht davon nichts. Er stellt einen Byte-Strom
aus der Ferne dar. Für dieses schmalere Problem reicht libvterm (MIT), und die
Compose-Darstellung hätten wir so oder so selbst schreiben müssen, weil
Termux' `TerminalView` eine klassische Android-View ist.

Die GPLv3 hätte also im Wesentlichen nur den Escape-Sequenz-Parser gekauft.

**Alle Lizenzen habe ich in der jeweiligen `LICENSE`-Datei nachgelesen, nicht
aus dem Gedächtnis behauptet** — festgehalten samt Commit-Stand in
`THIRD_PARTY_LICENSES.md`. Bei einer Entscheidung, die das gesamte Projekt
bindet, ist „ich bin mir ziemlich sicher" keine tragfähige Grundlage.

## Der erwartete Fallstrick, der keiner war

Ich hatte vorhergesagt, libvterm würde dieselbe Falle stellen wie iperf3:
Upstreams Makefile erzeugt drei `.inc`-Tabellen mit **Perl**, und ein von
Gradle angestoßener CMake-Lauf kann sich unter Windows nicht darauf
verlassen, eine POSIX-Shell zu finden.

Nachgesehen: Alle drei Dateien (`src/fullwidth.inc`,
`src/encoding/DECdrawing.inc`, `src/encoding/uk.inc`) liegen **fertig
generiert im Repository** und werden von `encoding.c` und `unicode.c` als
gewöhnliche Header eingebunden. Zur Bauzeit läuft kein Perl.

Weiter geprüft und ebenfalls unproblematisch: libvterm hat **gar keinen**
generierten Konfigurationsheader — die Versionsmakros stehen direkt in
`include/vterm.h`, und außerhalb der C-Standardbibliothek wird nichts
eingebunden. Damit entfällt das gesamte `iperf_config.h`-Verfahren aus Phase
5c. Neun `.c`-Dateien, reines C99, CMake baut sie direkt.

## Entwurf der JNI-Brücke

Zwei Regeln bestimmen `vterm_jni.c`:

**Kein JNI-Aufruf pro Zelle.** Die JNI-Grenze einmal pro Zeichen zu queren
wäre bei 80×24 schon ruinös. `nativeSnapshot` füllt stattdessen **ein**
Int-Array in einem Aufruf, vier Ints pro Zelle: Codepunkt, Vordergrund-RGB,
Hintergrund-RGB, Attributbits samt Zellbreite. Die Kotlin-Seite liest per
Indexrechnung (`VtermCell`) statt über Objekte — ein Objekt pro Zelle wäre
genau der Müll pro Bild, der ein Terminal ruckeln lässt.

**Nichts darf den Prozess mitnehmen.** Jeder Einstiegspunkt prüft seinen
Zeiger, und jeder Array-Zugriff wird an der **tatsächlichen** Array-Länge
begrenzt, nicht an der vom Aufrufer behaupteten. `VtermBridge` fängt zusätzlich
`UnsatisfiedLinkError` und alles andere ab, wie in `:native:icmp` und
`:native:iperf3`.

Weitere bewusste Festlegungen:

- **Farben werden nativ aufgelöst.** `vterm_screen_convert_color_to_rgb()`
  macht aus Palettenindizes und „Standardfarbe"-Markierungen konkretes RGB,
  damit Kotlin die Palette nie kennen muss.
- **Ein einziges „dirty"-Flag** statt feingranularer Damage-Rechtecke. Ein
  überflüssiges Neuzeichnen ist harmlos; ein falsches „nichts geändert" ist
  ein übler Fehler.
- **Designated Initializers** für `VTermScreenCallbacks`. Die Struktur ist
  über libvterm-Versionen gewachsen; positionsbasierte Initialisierung würde
  nach einem Update stillschweigend die falsche Funktion in den falschen Slot
  hängen.
- **Tastaturbytes werden gepuffert, nicht verworfen-und-vergrößert.** Läuft
  der Puffer über, wird gezählt (`nativeDroppedOutputBytes`), statt in einem
  Callback zu reallozieren, der keine Möglichkeit hat, ein Scheitern zu
  melden.

Die Enum-Werte in `VtermKey` sind aus `vterm_keycodes.h` **abgelesen**, nicht
fortgezählt: Die Folge bricht bei 14 ab, weil `VTERM_KEY_FUNCTION_0`
ausdrücklich auf 256 gesetzt ist und 256 Plätze für F-Tasten reserviert.
Blindes Weiterzählen hätte stillschweigend falsche Escape-Sequenzen gesendet.

## Bekannte Grenzen

- **Kein Scrollback.** Die `sb_pushline`-Rückrufe sind nicht angebunden;
  sichtbar ist nur der aktuelle Bildschirm. Kommt in 6c.
- **Nur `chars[0]` pro Zelle.** libvterm trägt bis zu sechs kombinierende
  Zeichen; wir nehmen das erste. Bewusste Grenze, keine Auslassung.
- **Fenstertitel** (`VTERM_PROP_TITLE`) wird nicht ausgewertet.

## Verifiziert

Der erste App-Build sagte über dieses Modul **nichts** aus: Gradle baut nur,
was `:app` tatsächlich braucht, und noch hängt nichts an `:native:vterm`. Ein
grüner Balken war hier also kein Beleg.

Deshalb direkt übersetzt, mit dem Ninja aus dem SDK gegen die von AGP
erzeugten Build-Dateien:

- **Alle elf Übersetzungseinheiten fehlerfrei**, **keine einzige Warnung**
  trotz `-Wall`.
- **Alle elf JNI-Symbole exportiert**, geprüft mit `llvm-nm` — genau die,
  die `VtermNative.kt` als `external fun` deklariert.
- **LOAD-Ausrichtung `0x4000`** (16384 Byte), geprüft mit `llvm-readelf`. Die
  16-KB-Anforderung ist erfüllt, diesmal vorab statt wie bei `:native:icmp`
  erst nach einer Warnung im Installer.
- 270 KB unstripped Debug für arm64-v8a.

Was damit **noch nicht** gezeigt ist: dass die Emulation zur Laufzeit richtig
rechnet. Übersetzen und Laden sind nicht Verhalten. Das zeigt sich erst, wenn
in 6c echte Ausgabe durch den Parser läuft.

## Nachtrag: derselbe Kodierungsfehler, zum zweiten Mal

Beim Nachtragen des Abschnitts „Verifiziert" habe ich diese Datei erneut mit
`perl -0pi` bearbeitet — exakt das Werkzeug, das ich in `PHASE5C_NOTES.md`
zwei Runden zuvor als Ursache für kaputte Zeichen dokumentiert und mir selbst
untersagt hatte. Ergebnis: dieselbe Doppelkodierung.

Schlimmer war der zweite Schritt. Ich ließ mein Reparaturskript darüber
laufen, das aber **pauschal jede** verdächtige Zeile eine UTF-8-Ebene
zurückdekodiert. Die frisch und korrekt eingefügten Zeilen waren nicht
doppelt kodiert — die hat es dadurch erst kaputtgemacht. Aus einer einheitlich
falschen Datei wurde eine gemischt falsche, was schwerer zu erkennen ist.

Zwei Lehren, die über diese Datei hinausgehen:

1. **Eine dokumentierte Lehre ist wertlos, wenn sie nicht die Werkzeugwahl
   ändert.** Für Dateien mit Nicht-ASCII-Inhalt schreibe ich ab jetzt die
   ganze Datei neu, statt sie stream-zu-editieren.
2. **Ein Reparaturskript braucht dieselbe Sorgfalt wie der Code, den es
   repariert.** Meines prüfte nur „sieht diese Zeile falsch aus", nicht „ist
   sie es wirklich" — und richtete auf den gesunden Zeilen Schaden an.

Die Datei wurde anschließend vollständig neu geschrieben.

---

Als Nächstes: **6b** — SSH-Transport mit mwiede/jsch (BSD 3-clause).

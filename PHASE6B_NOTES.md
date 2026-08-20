# Phase 6b – SSH-Transport

Zweiter Teil des Terminals: Verbindung, Authentifizierung und — der eigentliche
Inhalt dieser Phase — die **Host-Key-Prüfung**. Noch keine Oberfläche, die
kommt in 6c.

## Der Fund, der die Abhängigkeiten bestimmt hat

jschs eigene Dokumentation sagt: **ed25519 braucht Java 15+**, **x25519
braucht Java 11+**. Android stellt beides auf keinem von dieser App
unterstützten API-Level bereit. Die Folgen sind nicht theoretisch:

- `curve25519-sha256` ist der **Standard-Schlüsselaustausch** aktueller
  OpenSSH-Versionen. Ohne x25519 nicht verfügbar — ein Server, der nur
  moderne Verfahren anbietet, wäre schlicht nicht erreichbar.
- `ssh-ed25519` ist der **Standard-Hostschlüssel** aktueller OpenSSH-Versionen.
  Ohne ihn lässt sich ein Host, der nur einen ed25519-Schlüssel
  veröffentlicht, nicht verifizieren.

Deshalb ist Bouncy Castle hier keine Bequemlichkeit, sondern Voraussetzung.
Ohne es könnte der Client mit einem großen Teil der Server, für die er
existiert, gar nicht sprechen.

**Die Reihenfolge der JCE-Provider ist bewusst gewählt.** Android liefert
selbst einen abgespeckten Provider, der ebenfalls „BC" heißt — der
vollständige lässt sich also nicht danebenlegen, der Name kollidiert und der
Aufruf würde ignoriert. Der abgespeckte wird deshalb entfernt und der
vollständige **ans Ende** der Liste gehängt, nicht an den Anfang. So löst
jeder Algorithmus, den die Plattform selbst beherrscht, weiterhin auf den
Plattform-Provider auf — auch hardwaregestützte —, und Bouncy Castle füllt
nur die Lücken. Ihn nach vorn zu setzen würde stillschweigend sehr viel
unbeteiligte Kryptographie auf eine Software-Implementierung umlenken.

**Offen und ehrlich:** Bouncy Castle ist ein großes Artefakt, und die APK-Größe
war in diesem Projekt schon einmal ein Thema (59 → 29,8 MB). Wie viel R8 davon
wegschrumpft, weiß ich noch nicht — das messen wir, sobald der Build läuft.
Korrektheit zuerst, Größe danach gemessen statt geraten.

## Host-Key-Prüfung: der Teil, der stimmen muss

Aufbau in drei Schichten, damit die Entscheidung testbar und ohne jsch-Typen
prüfbar bleibt:

1. **`HostKeyVerifier`** — rein, ohne Android- und ohne jsch-Typen. Entscheidet
   `Trusted` / `Unknown` / `Changed`.
2. **`KnownHostsStore`** — DataStore mit kotlinx.serialization.
3. **`NetToolboxHostKeyRepository`** — die Brücke zu jsch.

Die tragenden Festlegungen:

**Kein Weg, die Prüfung abzuschalten.** Es gibt keinen „alles vertrauen"-Modus
und kein Flag. Ein SSH-Client, der jeden Schlüssel stillschweigend annimmt,
bietet den Anschein von Sicherheit ohne die Sache selbst.

**Die Brücke entscheidet nicht, sie berichtet.** `check()` fragt nie nach und
speichert nie. Sie hält fest, was sie gesehen hat, und gibt `NOT_INCLUDED`
bzw. `CHANGED` zurück — womit jsch die Verbindung abbricht. Erst danach wird
die Frage dem Benutzer gestellt, und nur ein **neuer** Verbindungsversuch nach
ausdrücklichem Vertrauen kann Erfolg haben. Damit besteht zu keinem Zeitpunkt
eine Verbindung gegen einen ungeprüften Schlüssel, auch nicht kurz.

**`add()` ist absichtlich leer.** jsch würde darüber einen Schlüssel
speichern, nachdem ein `UserInfo` zugestimmt hat. In einen von jsch
gesteuerten Rückruf hinein in den Vertrauensspeicher zu schreiben ist genau
die implizite Vertrauensbildung, die diese Klasse verhindern soll.

**Abbruch vor der Authentifizierung.** Weil die Prüfung Teil des Handshakes
ist, wird ein Passwort nie an einen Host gesendet, dessen Identität nicht
bestätigt ist.

**`Unknown` und `Changed` sind getrennte Fälle**, nicht zwei Abstufungen von
einem. Ersteinrichtung und möglicher Man-in-the-Middle verlangen völlig
verschiedene Warnungen; sie zusammenzufassen hieße, dem schwereren Fall die
Wortwahl des harmloseren zu leihen. Der `Changed`-Fall trägt zusätzlich den
**gespeicherten** Schlüssel mit, damit beide nebeneinander gezeigt werden
können, statt den Benutzer um Glauben zu bitten.

**Verglichen wird der Schlüssel, nie der Fingerabdruck.** Der Fingerabdruck
ist eine Darstellung für Menschen; ihn zur Identität zu machen würde die
Prüfung nur so stark machen wie diesen Hash. Ein Test deckt genau das ab.

**Schlüsseltyp und Port gehören zur Identität.** Ein Server bietet oft mehrere
Hostschlüsseltypen an — einem ed25519-Schlüssel zu vertrauen darf über einen
RSA-Schlüssel derselben Adresse nichts aussagen, sonst ließe sich die Prüfung
durch Typwechsel umgehen. Und zwei Ports auf einer Adresse können hinter einer
Portweiterleitung zwei völlig verschiedene Maschinen sein.

**Eine beschädigte Datei wirft, statt still leer zu sein.** Ein stilles
Zurückfallen auf „keine Hosts bekannt" würde aus einem kaputten
Vertrauensspeicher für jeden Server eine harmlos aussehende
Erstkontakt-Abfrage machen — genau der Moment, den ein Angreifer sich wünschen
würde.

## Eine bewusste Regelverletzung

`runBlocking` ist in diesem Projekt verboten, und in
`NetToolboxHostKeyRepository.check()` steht es trotzdem. Der Grund: jschs
Schnittstelle ist synchron und wird bereits auf einem Hintergrund-Thread
aufgerufen, den diese App besitzt und dieser Verbindung widmet. Die
Alternative gibt es nicht — „OK" zurückzugeben, während die Prüfung anderswo
noch läuft, würde den ganzen Zweck zunichtemachen. Die Stelle ist im Code
kommentiert, damit sie nicht als Nachlässigkeit durchgeht.

## Known Hosts nicht in Room

Abweichung vom Projektmuster, absichtlich: Die Menge ist klein und wächst nur,
eine relationale Ablage bringt nichts, und der sicherheitskritische Zustand in
**einer** kleinen Datei mit **einem** Serializer ist erheblich leichter zu
prüfen als eine Tabelle unter einem Dutzend. Nebeneffekt: keine
Schema-Migration auf einer Datenbank, die keinen Grund hat, sich zu ändern.

## Tests

Zwei reine Testklassen, keine Mocks — ein handgeschriebener DataStore-Ersatz,
der tatsächlich speichert, findet Reihenfolgefehler, die ein Mock durchwinkt.

- **`HostKeyVerifierTest`** — Erstkontakt, Wiedererkennen, Wechsel, Trennung
  nach Schlüsseltyp, Trennung nach Port, Ersetzen statt Anhäufen, Vergessen,
  und der Nachweis, dass der Fingerabdruck **nicht** die Vergleichsgrundlage
  ist.
- **`SshKeyTypeParsingTest`** — der Typ wird aus dem Schlüssel-Blob gelesen
  (RFC 4253, Abschnitt 6.6), also aus Netzwerkdaten. Geprüft: leer, zu kurz,
  Länge größer als der Blob, Länge null, negative Länge durch gesetztes
  höchstes Bit (`0xFFFFFFFF` als `Int` ist −1 und würde ohne Schutz in
  `String(bytes, offset, -1)` laufen), absurd langer Name.

## Nicht kompiliert — und warum diesmal ernsthaft versucht

Ich habe eine zwischengespeicherte Gradle-Distribution (8.14.3) gefunden und
dreimal versucht, `:feature:ssh:compileDebugKotlin` selbst laufen zu lassen:
mit Daemon, ohne Daemon, und über PowerShell. Jedes Mal scheiterte der
Gradle-Daemon mit `java.io.IOException: Unable to establish loopback
connection` — er kommt in dieser Umgebung an keine Loopback-Verbindung.

Damit ist **der gesamte Kotlin-Code dieser Phase ungeprüft**. Alle
jsch-Signaturen habe ich vorher im Quelltext der Fassung 2.28.6 nachgelesen
(`HostKeyRepository`, `HostKey`, `JSch.addIdentity`/`getSession`,
`ChannelSession.setPtyType`/`setPtySize`), nicht aus dem Gedächtnis — aber
richtig abgelesene Signaturen sind kein Ersatz für einen Übersetzungslauf.

Bei `:native:vterm` in 6a konnte ich das umgehen, weil sich der native Teil
mit Ninja direkt bauen ließ. Für Kotlin geht das nicht.

## Was 6c noch braucht

- Compose-Renderer für das Zeichengitter (ein einziges `Canvas`, kein
  Composable pro Zelle)
- Ein-/Ausgabeschleife: SSH-Stream → `VtermBridge.write`, Tastendrücke →
  `VtermBridge.readOutput` → SSH
- Die Host-Key-Dialoge — insbesondere die **deutlich unterschiedliche**
  Darstellung von `Unknown` gegenüber `Changed`
- Verbindungsprofile, Scrollback, Anbindung ans Dashboard

# Phase 4 – Mobilfunk, Teil 1: Live-Ansicht

Umgesetzt: `TelephonyCallback`-Pipeline mit Fallback für Android 9–11,
ARFCN/Band-Tabellen, Live-Ansicht mit Gauges und Rolling-Chart, Nachbarzellen mit
Delta zur Serving Cell, Multi-SIM.

**Noch offen:** Drive-Test-Logging-Service, Sitzungsverwaltung, Export
(CSV/GeoJSON/KML), Karte mit MapLibre, OpenCelliD-Anbindung.

## Bitte gegenprüfen: die Bandtabellen

`ArfcnBands` ist von Hand geschrieben. Die Zahlen stammen aus meinem Wissen über
3GPP TS 36.101 (E-UTRA) und TS 38.104 (NR), **nicht aus einer geprüften Quelle**.
Das ist die Stelle in diesem Projekt, an der ein Fehler am längsten unentdeckt
bliebe: eine um eins verschobene Bandgrenze liefert ein plausibel aussehendes
falsches Band.

Was ich dagegen getan habe:

- Die Tabelle deckt bewusst nur die in Europa verbreiteten Bänder plus die
  gängigen globalen ab. Außerhalb liefert sie `null`, und die UI zeigt dann die
  rohe ARFCN statt zu raten.
- NR wird **gerechnet statt tabelliert**: NR-ARFCN → Frequenz nach der Formel aus
  38.104 (drei Rasterbereiche), dann Frequenz → Band. Die Formel ist deutlich
  weniger fehleranfällig als eine ARFCN-Tabelle.
- `ArfcnBandsTest` prüft beide Kanten jedes LTE-Bands, alle drei NR-Raster und die
  Lücken zwischen Bändern.

Wenn du eine belastbare Quelle hast, gleiche vor allem die LTE-Bereiche ab.

## Entscheidungen

### Zwei Pipelines, weil minSdk 28

`TelephonyCallback` gibt es erst ab Android 12. Darunter ist der deprecated
`PhoneStateListener` die einzige Möglichkeit, also ist er drin — statt so zu tun,
als begänne das Feature bei API 31.

**Risiko, das auf einem Android-9-Gerät zu prüfen ist:** der Listener überschreibt
`onDisplayInfoChanged(TelephonyDisplayInfo)`. Die Klasse `TelephonyDisplayInfo`
existiert erst ab API 30. ART lädt Klassen träge, das sollte also nie geladen
werden — aber falls es auf einem echten Android 9 zu einem `NoClassDefFoundError`
kommt, muss der Listener in zwei Varianten aufgeteilt werden.

### 5G NSA ist nur über die Display-Info sichtbar

Die Zellliste zeigt bei NSA weiterhin die LTE-Ankerzelle. Erst
`TelephonyDisplayInfo.overrideNetworkType` verrät, dass NR danebenliegt. Deshalb
werden drei Callbacks zusammengeführt und der RAT nachträglich von LTE auf NR_NSA
korrigiert — mit `NR_SA` und `NR_NSA` als getrennten Werten, weil sie sich im Feld
unterschiedlich verhalten.

### Integer.MAX_VALUE wird zu null

Android meldet „nicht gemessen" als `Integer.MAX_VALUE`. Ein Log, das 2147483647
als RSRP speichert, ist nicht bloß kurios — es zerstört jeden Mittelwert, der
später daraus gerechnet wird. `CellInfoMapper` normalisiert das durchgehend, und
alle Metrikfelder sind nullable.

### SS-Werte statt CSI-Werte bei NR

`ssRsrp`/`ssRsrq`/`ssSinr` sind das, was ein Techniker mit „NR-RSRP" meint. Die
CSI-Werte beschreiben den Datenkanal und werden von Modems deutlich
uneinheitlicher gemeldet.

### Timing Advance ist eine Obergrenze, keine Entfernung

Ein LTE-TA-Schritt entspricht rund 78 m Umweglänge. Reflexionen und fehlende
Sichtverbindung machen die tatsächliche Entfernung kleiner, nie größer. Die UI
schreibt deshalb „höchstens ca. X m".

### Die Plattformgrenze steht im UI

Unter der Live-Ansicht steht, dass Android nur Zellen der eingelegten SIM meldet
und fremde Netze eine Signatur-Berechtigung erfordern. Das ist Abschnitt 3.1
deiner Spezifikation, und es gehört sichtbar in die App statt in eine Fußnote.

### Abtastung: Callback plus aktive Anfrage

Ab Android 10 aktualisiert sich die gecachte Zellliste im Takt der Plattform.
`requestCellInfoUpdate` ist der einzige Weg zu einem Messwert auf Abruf; der
Poller läuft im konfigurierten Intervall (Standard 2 s, 1–10 s einstellbar).
Der Flow ist `conflate()`d, damit das Modem die UI nicht überholt.

## Realistische Erwartungen beim Testen

- **Nachbarzellen fehlen auf vielen Geräten.** Etliche Modems melden sie nur im
  Leerlauf, manche gar nicht. Eine leere Liste ist kein Fehler der App.
- **Bandbreite und Timing Advance** melden längst nicht alle Modems.
- Im **Emulator** ist praktisch nichts davon zu sehen; das ist ein Feature für ein
  echtes Gerät mit SIM.

## Neue Tests

`ArfcnBandsTest` — LTE-Bandkanten beidseitig, unbelegte EARFCN-Lücken, alle drei
NR-Rasterbereiche, n78-Vorrang vor n77, UMTS und GSM, Timing-Advance-Umrechnung.

---

# Phase 4, Teil 2: Drive-Test-Logging

Umgesetzt: Foreground-Service, Sitzungsanlage, Positionsquelle ohne GMS,
gebatchte Room-Schreibvorgänge, Handover-Zählung, Sitzungsstatistik und die drei
Exportformate.

**Noch offen:** Sitzungsliste im UI mit Export-Auslösung, Import, Karte,
OpenCelliD.

## Entscheidungen

### Die Aufzeichnung lebt im Service, nicht im ViewModel

Deine Spezifikation verlangt in Abschnitt 7, dass laufende Messungen Rotation und
Prozesstod überleben. Ein ViewModel überlebt beides nicht. Der Zustand liegt
deshalb in einem `@Singleton`-Controller, den Service und UI teilen.

`START_NOT_STICKY`: eine Aufzeichnung, die das System ohne Zutun des Nutzers neu
startet, erzeugt eine Sitzung, die niemand begonnen hat und niemand deuten kann.

### Standort über `LocationManager`, nicht über Play Services

Abschnitt 11 schließt eine GMS-Abhängigkeit aus. Ein Drive-Test, der nur auf
Google-zertifizierten Geräten läuft, ist auf der Hardware, die Außendienstler
tatsächlich tragen, wertlos. GPS und Netzwerk-Provider werden beide abonniert —
GPS ist der genaue, der Netzwerk-Provider hält eine Sitzung im Gebäude oder Tunnel
am Leben.

Die letzte bekannte Position wird sofort eingespeist, damit die ersten Messwerte
Koordinaten haben statt auf einen Kaltstart-Fix zu warten.

### Gebatchte Schreibvorgänge, aber Flush vor dem Sitzungsende

20 Messwerte je Transaktion. Beim Stoppen wird **zuerst geleert, dann** die
Sitzung beendet — die Werte im Puffer sind Messungen, für die jemand gefahren ist
und die sich nicht wiederholen lassen.

### Handover heißt Zellwechsel, nicht Signalwechsel

`SessionAnalysis.countHandovers` zählt Änderungen der Cell ID und **überspringt**
Messwerte ohne Cell ID. Sonst würde eine kurze Meldelücke als zwei Handover
erscheinen. Getestet.

### Median statt Mittelwert in der Statistik

Ein einzelner tiefer Einbruch zieht einen Mittelwert nach unten und stellt eine
sonst brauchbare Strecke falsch dar. Die Statistik nennt zusätzlich den
schlechtesten und den besten Wert, damit der Einbruch nicht verschwindet.

### Export: drei Formate, ein reiner Codepfad

`MeasurementExporter` ist frei von Android-APIs und getestet. Die drei Punkte, an
denen ein Export still kaputtgeht, haben jeweils einen Test:

- **Dezimalpunkt statt Komma** in `Locale.ROOT` — ein deutsches Komma würde die
  CSV-Spalte spalten.
- **Messwerte ohne Position werden übersprungen**, nicht mit 0/0 geschrieben. Sonst
  landet jeder unpositionierte Messwert im Golf von Guinea.
- **XML-Escaping in KML**, sonst zerlegt ein Betreibername mit `&` das Dokument.

Fehlende Werte sind in CSV leere Zellen und in GeoJSON fehlende Schlüssel — nie
`null` als Text und nie 0.

### Service-Deklaration mit vollqualifiziertem Namen

Ein relativer Name in einem Bibliotheks-Manifest wird beim Merge gegen die
`applicationId` der App aufgelöst, nicht gegen den Namespace des Moduls. Der
Eintrag zeigte sonst auf eine Klasse, die es nicht gibt.

## Was beim Testen zu beachten ist

- **Benachrichtigungsberechtigung**: Ab Android 13 zeigt Android den Foreground
  Service ohne `POST_NOTIFICATIONS` nicht an. Das Bundle
  `PermissionBundle.SERVICE_NOTIFICATIONS` existiert, ist aber noch nicht vor dem
  Start abgefragt — bitte einmal manuell in den App-Einstellungen erteilen, bis
  ich das nachziehe.
- **Hintergrund-Standort** wird für die Aufzeichnung bei ausgeschaltetem Display
  gebraucht und muss separat erteilt werden.
- Der Puffer schreibt alle 20 Messwerte; bei 2 s Intervall also etwa alle 40 s.

## Neue Tests

| Testklasse | deckt ab |
|---|---|
| `MeasurementExporterTest` | CSV-Kopf und Zeilen, Dezimalpunkt, leere Felder, Quoting; GeoJSON-Koordinatenreihenfolge, Überspringen ohne Position, weggelassene Schlüssel; KML-Wohlgeformtheit, Farbstufen, Escaping |
| `SessionAnalysisTest` | Handover-Zählung inkl. Meldelücken und Zeitreihenfolge, Median gegen Ausreißer, positionierte Messwerte, RAT-Anteile, Dauer |

---

# Phase 4, Teil 3: Sitzungen und Export im UI

Damit ist die Schleife geschlossen: aufzeichnen → ansehen → exportieren.

## Entscheidungen

### Statistik erst beim Aufklappen

Jede Sitzung im Voraus zu analysieren würde jeden Messwert jeder Messfahrt lesen,
nur um eine Liste zu zeichnen, an der man vielleicht vorbeiscrollt. Die Werte
werden beim Aufklappen berechnet und dann behalten.

### Export läuft über einen suspendierenden Inhaltslieferanten

`rememberFileExporter` nimmt jetzt `suspend () -> String`. Der Inhalt entsteht
erst, nachdem ein Ziel gewählt wurde, und darf dafür die Datenbank lesen. Ein
abgebrochener Dialog liest weiterhin gar nichts.

Der Dateiauswähler wird aus einem `LaunchedEffect` geöffnet, nicht aus dem
Klick-Handler: so steht das gewünschte Format schon im Zustand, wenn der Exporter
seinen MIME-Typ bestimmt.

### Benachrichtigungsberechtigung vor dem Start

Die Lücke aus Teil 2 ist geschlossen. Ab Android 13 ist ein Foreground Service
ohne `POST_NOTIFICATIONS` unsichtbar — eine laufende Aufzeichnung wäre weder zu
bemerken noch zu stoppen. Der Startknopf fragt deshalb zuerst die Berechtigung an
und beschriftet sich entsprechend. Unter Android 13 ist das Bundle leer, der
Knopf startet also direkt.

## Damit ist Phase 4 abgeschlossen — bis auf die Karte

Offen bleiben aus Abschnitt 4.1: **Karte mit MapLibre**, **OpenCelliD-Anbindung**
und der **Import** eigener Dumps. Die gehören zusammen und sind das nächste
Arbeitspaket.

---

# Phase 4, Teil 4: Karte

Umgesetzt: MapLibre-Karte mit OSM-Rasterkacheln, Messpunkte und bekannte
Standorte als GeoJSON-Quellen, Layer-Schalter, Sitzungsauswahl, Import eines
OpenCelliD-CSV-Dumps, Attribution.

## Ungeprüft: die MapLibre-API

`MapLibreMapView.kt` ist die **einzige** Datei im Projekt, die MapLibre berührt.
Die Paketnamen (`org.maplibre.android.*`) und Signaturen (`MapView.getMapAsync`,
`Style.Builder().fromJson`, `GeoJsonSource`, `CircleLayer`, `PropertyFactory`,
`Expression.match`) sind nach bestem Wissen geschrieben, aber gegen keine
Dokumentation geprüft — genau der Fall, den Abschnitt 1 deiner Spezifikation
anspricht.

Deshalb der Zuschnitt: alles, was die Karte anzeigt, entsteht als fertiger
GeoJSON-String in `MapGeoJson`, rein und getestet. Weicht die MapLibre-API ab,
ist der Schaden auf diese eine Datei begrenzt.

**Die OpenCelliD-Online-API habe ich bewusst weggelassen.** Deren Endpunkt und
Parameter könnte ich hier ebenfalls nur raten. Stattdessen der CSV-Dump-Import,
den Abschnitt 3.1 ohnehin für den Offline-Betrieb vorsieht und dessen Format
dokumentiert und stabil ist.

## Entscheidungen

### GeoJSON-Quelle statt Marker

Wie in Abschnitt 7 gefordert. Marker sind Views; zehntausend davon überleben das
erste Verschieben der Karte nicht. Die Farbe kommt aus einem `quality`-Attribut
der Daten, nicht aus einer Farbe im GeoJSON — die Palette gehört dem Stil, die
Bedeutung den Daten. Dieselben Stufen treiben Karte, Liste und KML-Export.

### Ausdünnen wird benannt

Über 10.000 Punkten wird für die Darstellung ausgedünnt, und der Screen sagt es:
„Angezeigt: 8.000 von 42.000". Erster und letzter Punkt bleiben immer erhalten,
sonst begänne eine Messfahrt scheinbar zu spät und endete zu früh. Der Export
enthält weiterhin alles.

### Bekannte Zellen nur im Kartenausschnitt der Fahrt

Ein Länder-Dump hat Millionen Zeilen. Geladen wird die Bounding Box der
aufgezeichneten Strecke plus etwa ein Kilometer Rand, gedeckelt bei 5.000 Zellen.

### Import gestreamt und gebatcht

Zeile für Zeile gelesen, alle 2.000 Zeilen geschrieben. Eine kaputte Zeile wird
übersprungen, nicht der ganze Import abgebrochen — ein Fehler in einer
Megabyte-Datei darf den Nutzer nicht die ganze Arbeit kosten. `0,0` als Position
wird verworfen: das ist der übliche Platzhalter für „unbekannt" und läge sonst im
Golf von Guinea.

### Kachel-Nutzungsbedingungen

Die Karte nutzt die Standard-OSM-Kacheln. Die sind ausdrücklich nur für geringe
Last gedacht, Massenabruf ist untersagt. **Für eine Veröffentlichung muss hier
eine eigene oder kommerzielle Kachelquelle hinein** — das Feld für den
MapTiler-Key liegt in den Einstellungen bereits bereit. Die Attribution für OSM
und OpenCelliD steht sichtbar auf der Karte, wie Abschnitt 9 es verlangt.

## Neue Tests

| Testklasse | deckt ab |
|---|---|
| `MapGeoJsonTest` | FeatureCollection, Längengrad zuerst, Überspringen ohne Position, Qualitätsstufen, weggelassene Metriken, Downsampling inkl. erstem/letztem Punkt und Reihenfolge |
| `OpenCellIdCsvTest` | vollständige Zeile, Spaltenreihenfolge lon/lat, Kopfzeile, kaputte Zeilen, 0/0-Platzhalter, unmögliche Koordinaten, Radio-Zuordnung |

## Was aus Abschnitt 4.1 offen bleibt

- **OpenCelliD-Online-API** samt nutzereigenem Key
- **Sektor-Keulen** aus Azimut-Daten (der CSV-Dump liefert keinen Azimut)
- **Tap auf eine Zelle** mit Detail-Sheet und Messhistorie
- **Linie vom Standort zur Serving Cell**
- **Offline-MBTiles-Import**

## Nächster Schritt

Phase 5: das NDK-Modul — `:native:icmp` für echtes ICMP und Traceroute, danach
`:native:iperf3`.

# Phase 1 – Fundament

Umgesetzt: `:core:common`, `:core:ui`, `:core:permissions`, `:core:database`,
`:core:datastore`. Alle Feature-Screens und das Dashboard wurden auf das neue
Design System umgestellt.

## Nicht verifiziert

Wie in Phase 0: der Build wurde hier nicht ausgeführt. Der Abnahmetest ist

```bash
.\gradlew.bat :app:assembleDebug test
```

Der `test`-Task deckt die fünf neuen Testklassen in vier Modulen ab.

## Entscheidungen

### 1. DataStore mit kotlinx.serialization statt Proto

Wie in Phase 0 vorgeschlagen und jetzt umgesetzt. `UserSettingsSerializer` liest
mit `ignoreUnknownKeys = true`, ein Downgrade der App verliert also keine
Einstellungen, und eine kaputte Datei wird als `CorruptionException` gemeldet,
worauf DataStore auf die Defaults zurückfällt. Getestet in
`UserSettingsSerializerTest`.

Der Wechsel auf Proto wäre ein Austausch genau dieser einen Klasse.

### 2. `material-icons-extended` entfernt

Das war der Hauptgrund für die 59 MB der Phase-0-APK. Ersetzt durch 15 eigene
Vektor-Drawables in `:core:ui/res/drawable` plus `NetToolboxIcons`. Die Icons sind
selbst gezeichnet, nicht aus dem Material-Set kopiert – damit gibt es auch keine
Lizenzfrage bei der F-Droid-Verteilung.

**Bitte beim ersten Start visuell prüfen.** Ich habe die Pfaddaten von Hand
geschrieben und konnte sie nicht rendern. Falls ein Icon verzerrt aussieht,
sag mir welches, dann korrigiere ich die Geometrie.

Die Größe der neuen APK ist der zweite interessante Messwert nach dem Build.

### 3. Permission-Historie in SharedPreferences, nicht in DataStore

`PermissionCoordinator` muss beim Komponieren synchron beantworten können, ob eine
Berechtigung schon einmal angefragt wurde – nur so lässt sich "noch nie gefragt"
von "dauerhaft abgelehnt" unterscheiden. DataStore ist asynchron. Es sind drei
Booleans Buchführung, keine Nutzereinstellung.

### 4. Fünf Qualitätsstufen statt drei

`SignalQuality` hat EXCELLENT/GOOD/FAIR/POOR/BAD plus UNKNOWN. UNKNOWN ist
bewusst kein Synonym für BAD: "Modem meldet nichts" und "Signal ist schlecht"
führen zu völlig verschiedenen Handlungen im Feld.

Aus demselben Grund sind in `CellSampleEntity` fast alle Funkfelder nullable. Eine
0 statt null würde in jedem späteren Mittelwert als Messwert mitgezählt.

### 5. Downsampling mit wählbarer Strategie

`LineChart` reduziert über `BucketStrategy.MEAN|MIN|MAX`. Für Signalstärke ist der
schlechteste Wert im Fenster der handlungsrelevante (MIN), für RTT der Ausreißer
nach oben (MAX). Ein fest verdrahteter Mittelwert würde beides glattbügeln – genau
die Ereignisse, wegen derer man misst. Getestet in `LineChartTest`.

## Was bewusst noch fehlt

- **Kein Settings-Screen.** `SettingsRepository` und `UserSettings` existieren und
  sind an das Theme angeschlossen (`MainViewModel` → `NetToolboxTheme`), aber es
  gibt noch keine UI zum Ändern. Die gehört zu Phase 2, wo auch die ersten Tools
  Einstellungen brauchen.
- **`PermissionGate` ist noch nirgends eingebaut.** Die Feature-Screens sind
  weiterhin Platzhalter; das Gate kommt mit dem ersten Screen, der wirklich
  Berechtigungen braucht (Phase 3, WLAN).
- **Keine Room-Migrationen.** Version 1, Schema-Export aktiv. Ab Version 2 gilt
  AutoMigration, wo möglich.
- **`fallbackToDestructiveMigration` ist absichtlich nicht gesetzt.** Messdaten aus
  Feldarbeit lassen sich nicht wiederholen; eine fehlende Migration soll im
  Development laut scheitern statt still zu löschen.

## Neue Tests

| Modul | Test | deckt ab |
|---|---|---|
| `:core:common` | `OutcomeTest` | Loading/Failure-Semantik, Cancellation wird nicht verschluckt |
| `:core:common` | `SignalQualityTest` | Schwellwerte, UNKNOWN, clamping |
| `:core:ui` | `LineChartTest` | Downsampling-Strategien |
| `:core:datastore` | `UserSettingsSerializerTest` | Round-Trip, unbekannte Felder, Korruption |
| `:core:permissions` | `PermissionBundleTest` | SDK-Gating, Trennung des Hintergrund-Standorts |

## Nächster Schritt

Phase 2: Tools ohne NDK – Ping (Prozess-Fallback), DNS, Subnetzrechner, WOL,
Port-Scanner, IP-Scanner, HTTP/TLS-Inspector, jeweils mit History und Export.

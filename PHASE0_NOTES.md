# Phase 0 – Notizen, Annahmen und offene Entscheidungen

## Was Phase 0 liefert

Ein lauffähiges Gerüst: Gradle-Setup mit Version Catalog, 11 Module, Hilt, Theme,
Navigation Compose mit type-safe Routes, Bottom Bar mit vier Zielen und ein
Dashboard mit Schnellzugriff-Kacheln. Alle Feature-Screens sind echte,
themed Composables (kein `TODO()`), damit der Navigationsgraph vom ersten Build an
auf dem Gerät durchlaufen werden kann.

## Nicht verifiziert

Auf dem Rechner, auf dem dieses Gerüst erzeugt wurde, waren weder JDK noch Gradle
noch das Android SDK installiert. **Der Build wurde nicht ausgeführt.** Der erste
`./gradlew :app:assembleDebug` ist der eigentliche Abnahmetest von Phase 0.

Der Gradle-Wrapper ist unvollständig: `gradle/wrapper/gradle-wrapper.properties`
existiert, `gradle-wrapper.jar` und die Startskripte fehlen (Binärdateien lassen
sich hier nicht erzeugen). Einmalig nachholen mit einem lokal installierten Gradle:

```bash
gradle wrapper --gradle-version 8.14.3
```

Alternativ legt Android Studio den Wrapper beim ersten Öffnen des Projekts an.

## Versionen – bitte gegenprüfen

Die Versionen in `gradle/libs.versions.toml` sind nach bestem Wissen gesetzt, aber
nicht gegen ein Repository aufgelöst worden. Vor dem ersten Build prüfen:

| Abhängigkeit | gesetzt | Anmerkung |
|---|---|---|
| AGP | 8.11.1 | muss compileSdk 36 unterstützen (ab 8.9.1) |
| Gradle | 8.14.3 | muss zur AGP-Version passen |
| Kotlin | 2.2.0 | |
| KSP | 2.2.0-2.0.2 | Präfix muss exakt der Kotlin-Version entsprechen |
| Compose BOM | 2025.06.01 | |
| Hilt | 2.56.2 | muss zur KSP/Kotlin-Version passen |
| Room | 2.7.1 | |
| MapLibre | 11.8.1 | erst in Phase 4 aktiv |

Wenn eine davon nicht existiert, meldet Gradle das beim ersten Sync eindeutig –
dann die nächstliegende reale Version eintragen. Ich habe hier nicht geraten und
so getan, als sei es geprüft.

## Abweichungen von der Spezifikation

1. **`:core:datastore` – Proto vs. kotlinx.serialization.**
   Die Spezifikation nennt "DataStore Proto" als nicht verhandelbar. Proto-DataStore
   zieht das `protobuf-gradle-plugin` samt `protoc`-Toolchain in den Build. Ein
   typisiertes DataStore mit einem `Serializer`, der `kotlinx.serialization` nutzt,
   liefert dieselbe Typsicherheit ohne zusätzliche Toolchain und ohne
   Codegenerierung – und `kotlinx.serialization` ist ohnehin für die type-safe
   Routes im Projekt. **In Phase 1 so umgesetzt** – siehe `PHASE1_NOTES.md`.

2. **Keine Convention-Plugins (`build-logic`).**
   Die elf Modul-Build-Dateien wiederholen den Android-/Kotlin-Block. Das ist
   bewusst so: explizit und ohne zusätzliche Composite-Build-Mechanik, solange die
   Konfiguration noch in Bewegung ist. Sobald sie in Phase 2 stabil ist, lohnt der
   Umbau auf `build-logic` mit `nettoolbox.android.feature` &co.

3. **`:native:icmp` und `:native:iperf3` sind in `settings.gradle.kts`
   auskommentiert.** Leere NDK-Module ohne CMake-Quellen brechen den Build. Sie
   werden in Phase 5 eingehängt.

4. **`android:extractNativeLibs` steht nicht im Manifest.** Stattdessen setzt
   `app/build.gradle.kts` `packaging { jniLibs.useLegacyPackaging = true }` – das
   ist der von AGP 8 vorgesehene Weg und erzeugt genau dasselbe Manifest-Attribut.
   Das Attribut zusätzlich von Hand zu setzen, würde nur eine Warnung erzeugen.

5. **JUnit5 ohne das `de.mannodermaus.android-junit5`-Plugin.** Für reine
   JVM-Unit-Tests genügt `useJUnitPlatform()`, was in jedem Modul gesetzt ist. Das
   Plugin wird erst gebraucht, wenn Instrumented Tests auf JUnit5 laufen sollen –
   Compose-UI-Tests laufen ohnehin auf JUnit4 (`androidx.compose.ui.test.junit4`).

## Bewusst noch nicht drin

- Kein Permission-Handling: `:core:permissions` ist ein leeres Modul. Das Dashboard
  zeigt deshalb noch keine Live-Kacheln – ein Dashboard, das ohne Datenquelle
  Signalwerte anzeigt, wäre erfunden.
- Keine Room-Entities, kein Schema. Kommt mit Phase 1 samt Schema-Export.
- Kein Release-Signing (Phase 7). Der Release-Build ist derzeit debug-signiert und
  darf so nicht verteilt werden.
- Launcher-Icon ist ein Platzhalter (vier Signalbalken als Vektor).

## Nächster Schritt

Phase 1: `:core:permissions`, `:core:database`, `:core:datastore`, `:core:ui`
(Design System: Gauge, Line-Chart, ResultActionBar, Empty-/Error-/Permission-States).

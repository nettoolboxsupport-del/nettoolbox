# Datenschutzerklärung – NetToolbox

Stand: 23. September 2026

## Kurzfassung

NetToolbox erhebt keine personenbezogenen Daten, überträgt keine Daten an den
Entwickler oder an Dritte und enthält keine Analyse-, Werbe- oder
Tracking-Bibliotheken. Alle Messdaten bleiben auf dem Gerät.

## Welche Daten die App verarbeitet

NetToolbox ist ein Diagnosewerkzeug für Netzwerktechniker. Um seine Aufgabe zu
erfüllen, verarbeitet es die folgenden Daten **ausschließlich lokal auf dem
Gerät**:

- **Standortdaten.** Android gibt Mobilfunk- und WLAN-Messwerte nur an Apps
  heraus, die über die Standortberechtigung verfügen. Zusätzlich werden
  Positionen bei einer vom Nutzer gestarteten Messfahrt aufgezeichnet, damit
  Messwerte einem Ort zugeordnet werden können.
- **Mobilfunkdaten.** Netzbetreiber, Zellkennungen, Signalstärken, Funktechnik.
- **WLAN-Daten.** Erkannte Netze, Kanäle, Signalstärken, Verschlüsselungsart.
- **Netzwerkdaten.** Ergebnisse von Ping, Traceroute, DNS-Abfragen,
  Portprüfungen, Durchsatzmessungen und HTTP-/TLS-Untersuchungen, die der
  Nutzer selbst auslöst.
- **Eingegebene Verbindungsdaten.** Serveradressen, Benutzernamen und, sofern
  der Nutzer sie speichert, SSH-Zugangsdaten und vertraute Hostschlüssel.
- **Dateien in der Freigabe.** Dateien, die der Nutzer in die Freigabe des
  Dateiservers importiert oder die ein berechtigter Client hochlädt.
- **Konten des Dateiservers.** Vom Nutzer angelegte Benutzernamen und
  Passwörter für FTP und SSH sowie hinterlegte öffentliche SSH-Schlüssel.
- **Mitschnitte der seriellen Konsole.** Nur wenn der Nutzer den Mitschnitt
  einschaltet; standardmäßig ist er aus.

## Wo diese Daten liegen

In der privaten App-Ablage des Geräts, auf die andere Apps keinen Zugriff haben.
Es gibt keinen Server des Entwicklers und kein Benutzerkonto.

Gespeicherte SSH-Zugangsdaten und die Konten des Dateiservers liegen in der
privaten App-Ablage. Die Passwörter der Dateiserver-Konten werden im Klartext
gespeichert, damit die App sie zum Abtippen am Zielgerät wieder anzeigen kann.
Sie sind damit vor anderen Apps geschützt, aber **nicht** gegen jemanden, der
Zugriff auf ein entsperrtes Gerät hat. Wer besonders schutzbedürftige Zugänge
verwaltet, sollte Passwörter nicht speichern.

Die **Freigabe des Dateiservers** – einschließlich der Konsolenmitschnitte unter
„Console-Logs" – liegt im app-eigenen Bereich des gemeinsamen Speichers. Andere
Apps können sie nicht lesen, sie ist aber über ein USB-Kabel von einem Computer
aus zugänglich, sobald das Gerät entsperrt ist. Das ist gewollt, damit Dateien
auch ohne die App hinein- und herausgelangen. Ein Konsolenmitschnitt kann
Gerätekonfiguration mit Passwort-Hashes enthalten; er sollte entsprechend
behandelt werden.

## Datenübermittlung

Es findet keine automatische Übermittlung statt. Daten verlassen das Gerät nur
in den folgenden Fällen, und alle löst der Nutzer selbst aus:

1. **Export.** Der Nutzer wählt über die Systemauswahl ein Ziel für eine
   Export-Datei. Wohin sie geht, entscheidet allein er.
2. **Verbindungen, die der Nutzer aufbaut.** Ping, DNS-Abfragen, SSH-Sitzungen,
   Durchsatzmessungen und Kartenkacheln erzeugen naturgemäß Netzwerkverkehr zu
   den vom Nutzer angegebenen oder für die Karte benötigten Gegenstellen. Dabei
   werden keine Nutzungsdaten an den Entwickler gesendet.
3. **Dateiserver.** Solange der Nutzer den Dateiserver gestartet hat, können
   Clients im Netz Dateien aus der Freigabe abrufen und – sofern der Nutzer das
   erlaubt – Dateien hochladen. Bei FTP und SSH geschieht das nur mit einem vom
   Nutzer angelegten Konto; TFTP kennt protokollbedingt keine Anmeldung, worauf
   die App ausdrücklich hinweist. Der Dateiserver läuft nie ohne Zutun des
   Nutzers, zeigt eine Benachrichtigung, solange er aktiv ist, und beendet sich
   standardmäßig nach 60 Minuten selbst.
4. **Serielle Konsole.** Eingaben in der Konsole gehen über das USB-Kabel an
   das angeschlossene Gerät. Dabei entsteht kein Netzwerkverkehr.

Kartenkacheln werden von OpenStreetMap geladen. Dabei sieht deren Infrastruktur
technisch bedingt die IP-Adresse des Geräts. Es gilt die Datenschutzerklärung
der OpenStreetMap Foundation.

## Berechtigungen und ihr Zweck

| Berechtigung | Zweck |
|---|---|
| Standort (genau/ungefähr) | Voraussetzung für Mobilfunk- und WLAN-Messwerte; Positionsbezug bei Messfahrten |
| Telefonstatus lesen | Netzbetreiber, Zellkennung, Signalstärke |
| WLAN-Status und -Änderung | Netzsuche, Kanalanalyse, Multicast für Gerätesuche |
| Geräte in der Nähe (Android 13+) | Ersetzt die Standortabfrage bei der WLAN-Suche |
| Internet und Netzwerkstatus | Die Diagnosewerkzeuge und der Dateiserver |
| Benachrichtigungen | Anzeige laufender Messfahrten, des iperf3-Servers und des Dateiservers |
| Vordergrunddienst | Damit eine laufende Messung oder Dateiübertragung nicht abbricht, wenn das Display ausgeht |
| Gerät wach halten | Damit eine Dateiübertragung bei ausgeschaltetem Display weiterläuft |

Für die serielle Konsole wird keine Berechtigung angefordert: Android fragt beim
ersten Verbinden mit einem USB-Adapter, ob die App dieses eine Gerät benutzen
darf.

Ein Hintergrund-Standortzugriff wird **nicht** angefordert.

## Rechte der Nutzer

Da keine Daten den Entwickler erreichen, gibt es dort nichts, wozu Auskunft,
Berichtigung oder Löschung erteilt werden könnte. Alle lokal gespeicherten Daten
lassen sich in der App löschen oder vollständig durch Löschen der App-Daten
beziehungsweise Deinstallation entfernen.

## Kinder

Die App richtet sich an Fachpublikum und ist nicht für Kinder bestimmt.

## Änderungen

Änderungen dieser Erklärung werden an dieser Stelle veröffentlicht, mit
aktualisiertem Datum.

## Verantwortlich und Kontakt

Verantwortlich: Robin Fodor
Kontakt: nettoolboxsupport@gmail.com

Anfragen zu dieser App und zum Datenschutz gehen an diese Adresse.

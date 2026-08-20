# Datenschutzerklärung – NetToolbox

Stand: 18. August 2026

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

## Wo diese Daten liegen

In der privaten App-Ablage des Geräts, auf die andere Apps keinen Zugriff haben.
Es gibt keinen Server des Entwicklers und kein Benutzerkonto.

Gespeicherte SSH-Zugangsdaten liegen in der privaten App-Ablage. Sie sind damit
vor anderen Apps geschützt, aber **nicht** gegen jemanden, der Zugriff auf ein
entsperrtes Gerät hat. Wer besonders schutzbedürftige Zugänge verwaltet, sollte
Passwörter nicht speichern.

## Datenübermittlung

Es findet keine automatische Übermittlung statt. Daten verlassen das Gerät nur
in zwei Fällen, und beide löst der Nutzer selbst aus:

1. **Export.** Der Nutzer wählt über die Systemauswahl ein Ziel für eine
   Export-Datei. Wohin sie geht, entscheidet allein er.
2. **Verbindungen, die der Nutzer aufbaut.** Ping, DNS-Abfragen, SSH-Sitzungen,
   Durchsatzmessungen und Kartenkacheln erzeugen naturgemäß Netzwerkverkehr zu
   den vom Nutzer angegebenen oder für die Karte benötigten Gegenstellen. Dabei
   werden keine Nutzungsdaten an den Entwickler gesendet.

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
| Internet und Netzwerkstatus | Die Diagnosewerkzeuge selbst |
| Benachrichtigungen | Anzeige laufender Messfahrten und des iperf3-Servers |
| Vordergrunddienst | Damit eine laufende Messung nicht abbricht, wenn das Display ausgeht |

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

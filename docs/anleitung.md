# Rosenweg Galerie – Anleitung

Galerie für die Smartphones der Siedlung Rosenweg in Kaiseraugst. Neben den Bildern auf dem Telefon zeigt sie die Fotos und Videos der Person aus der Rosenweg-Nextcloud, also auch das, was Rosenweg Kamera dorthin hochgeladen hat.

## Installation

- **Geteilte Rosenweg-Telefone:** Rosenweg Gerät installiert die App automatisch aus dem App-Katalog der Telefonanlage und hält sie aktuell.
- **Eigenes Handy:** APK von [www.rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) herunterladen und installieren. Danach hält der Rosenweg Updater die Rosenweg-Apps aktuell.

Die App gibt es nicht im Play Store und nicht auf F-Droid. Sie ist mit dem Rosenweg-Schlüssel signiert; Fassungen anderer Herkunft lassen sich nicht darüber installieren.

## Im Rosenweg

- Chips "Telefon" und "Nextcloud": die Nextcloud-Ansicht zeigt Fotos und Videos aus `Persönlich` und startet im Ordner `Kamera`.
- **Rosenweg-Nextcloud:** Anmeldung über den Login der Nextcloud mit dem Rosenweg-Konto im Browser; die App erhält ein eigenes App-Passwort, nie das eigentliche Passwort. Auf geteilten Telefonen kommt der Zugang von Rosenweg Gerät. Hochgeladen wird über eine Warteschlange, sobald Netz da ist; lokal gelöscht wird erst, wenn die Nextcloud den Empfang bestätigt hat. Beim ersten Start fragt die App einmal, ob man sich anmelden möchte ("Nein, danke" wird gemerkt, Anmelden geht weiter über die Einstellungen).
- **Einstellungen pro Person:** Die Einstellungen der App liegen in der Nextcloud der Person (`Persönlich/.rosenweg/`) und sind auf jedem Telefon wieder da, auf dem sie angemeldet ist. Gerätebezogenes (Widgets, Pfade, Zähler) bleibt auf dem Gerät.
- **Rosenweg Updater:** Auf Handys ohne Rosenweg Gerät prüft die App den App-Katalog der Telefonanlage und bietet Aktualisierungen für alle installierten Rosenweg-Apps an. Jede APK wird vorher geprüft (Prüfsumme, Paketname, Version, Rosenweg-Signatur); Android lässt jede Installation bestätigen. Auf geteilten Telefonen übernimmt das Rosenweg Gerät.
- Nextcloud-Ansicht: Knopf für Bearbeiten, Papierkorb und Browser; Bearbeitetes wird als neue Datei `<name> (bearbeitet)` neben dem Original gespeichert. Umbenennen und Löschen auch in der Fotoansicht, sie wirken auf die Datei in der Nextcloud. Ein Banner zeigt wartende Kamera-Uploads.
- Mit Rosenweg Gerät startet die App direkt in der Nextcloud, ohne Chip für den Telefon-Speicher.

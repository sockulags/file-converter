# Egna fonter

TrueType-filer (`.ttf`) här kopieras till `/opt/fonts` i Docker-imagen och blir tillgängliga
både för LibreOffice och för HTML-mallarna.

- Lägg bara in fonter ni har licens att bädda in i PDF.
- Använd TrueType, inte OpenType/CFF (`.otf`). CFF-fonter bäddas in på ett sätt som PDF/A-1 inte
  alltid godtar, och fontconfig-regeln i `docker/fonts.conf` stänger av dem.
- Har ni licens för t.ex. Calibri kan den läggas här. Annars används den måttkompatibla Carlito.

# file-converter

Konverteringstjänst med samma API som Gotenberg 8 för routerna nedan, men med **validerad PDF/A-1a
och PDF/A-1b**. Byggd för att ersätta Gotenberg där fonter, grafik och PDF/A har strulat.

Alla filer som begärs som PDF/A normaliseras och valideras med [veraPDF](https://verapdf.org)
innan de skickas tillbaka. En fil som inte klarar valideringen ger `422` med en förklaring i
stället för en trasig PDF.

## Routes

| Route | Indata | Motor |
|---|---|---|
| `POST /forms/libreoffice/convert` | Office-filer (docx, xlsx, pptx, odt, rtf …) | LibreOffice via JODConverter |
| `POST /forms/chromium/convert/html` | `index.html` + resurser (bilder, css, ttf) | openhtmltopdf |
| `POST /forms/pdfengines/convert` | PDF-filer | PDFBox (normalisering) |
| `POST /forms/pdfengines/merge` | PDF-filer, slås ihop i bokstavsordning | PDFBox |
| `GET /health` | – | `503` tills LibreOffice är igång |

Port `3000`, precis som Gotenberg.

### Formulärfält

| Fält | Routes | Beskrivning |
|---|---|---|
| `pdfa` | alla | `PDF/A-1a` eller `PDF/A-1b`. Krävs för `pdfengines/convert`. |
| `merge` | libreoffice | `true` slår ihop resultaten till en PDF |
| `paperWidth`, `paperHeight`, `landscape` | chromium/html | Tal utan enhet är tum (som Gotenberg), annars `mm`, `cm`, `in`, `pt`, `px` |
| `marginTop`, `marginRight`, `marginBottom`, `marginLeft` | chromium/html | Som ovan |
| `pdfaStrict` | alla | `false` returnerar filen även om valideringen fallerar (tillägg) |

Flera filer utan `merge` ger en zip, som i Gotenberg. Huvudet `Gotenberg-Output-Filename` sätter filnamnet.

### Svar

- `X-Pdfa-Level: PDF/A-1a` och `X-Pdfa-Compliant: true|false` när PDF/A har begärts.
- Fel som `application/problem+json`. `violations` anger exakt vad som stoppade PDF/A:

```json
{
  "status": 422,
  "detail": "PDF-filen kan inte göras om till PDF/A-1 utan att utseendet ändras.",
  "violations": ["Transparens på sida 1 (tillåts inte i PDF/A-1)."]
}
```

### Exempel

```bash
curl -F files=@beslut.docx -F pdfa=PDF/A-1a http://localhost:3000/forms/libreoffice/convert -o beslut.pdf
curl -F files=@mall.html\;filename=index.html -F files=@logo.png -F pdfa=PDF/A-1a \
     http://localhost:3000/forms/chromium/convert/html -o brev.pdf
curl -F files=@kund.pdf -F pdfa=PDF/A-1a http://localhost:3000/forms/pdfengines/convert -o kund-pdfa.pdf
```

## Så blir filerna PDF/A

**Office:** LibreOffice exporterar taggad PDF/A-2, som tillåter transparens, och normaliseringen gör
om den till PDF/A-1. LibreOffice:s eget PDF/A-1-läge används inte, eftersom det kastar hela
taggstrukturen så fort dokumentet innehåller transparens, t.ex. en PNG med alfakanal.

**HTML-mallar:** openhtmltopdf skapar taggad PDF/A direkt från HTML-strukturen (rubriker, listor,
tabeller, `alt`-texter). `lang` på `<html>` följer med.

**Befintliga PDF:er** (`pdfengines/convert`, `merge`): normaliseringen i `PdfaNormalizer` rättar
utan att rendera om innehållet:

| Rättas | Avvisas med förklaring |
|---|---|
| XMP-metadata och datum (synkas med Info) | Kryptering och lösenord |
| CharSet för Type 1-delmängder (OpenType/CFF-fonter) | |
| Färgprofil (sRGB OutputIntent) | Redan signerade filer (signaturen skulle brytas) |
| CIDSet för fontdelmängder (saknas i Word/Chromium-PDF) | Fonter som inte är inbäddade |
| Bilder med alfakanal (läggs mot den bakgrund de ligger på, se nedan), JPEG2000 | Verklig transparens (`opacity`, blandningslägen) |
| Utskriftsflaggor och actions på annoteringar, JavaScript, bifogade filer | För 1a: avsaknad av taggstruktur |
| xref-strömmar (PDF/A-1 bygger på PDF 1.4) | |

### Bilder med transparens

PDF/A-1 förbjuder transparens, även i bilder (PNG med alfakanal, logotyper). I stället för att lägga
bilden mot vitt renderas sidan fram till den punkt där bilden ritas. Utsnittet bakom bilden
(bakgrundsfärg, toningar, andra bilder, text) blir bildens nya bakgrund, och bilden ersätts med en
ogenomskinlig version. Resultatet ser likadant ut, och text bakom bilden förblir sökbar.

Används samma bild på flera ställen mot olika bakgrunder, eller inuti en transparensgrupp, läggs den
mot vitt.

### Taggstruktur

För PDF/A-1a krävs dessutom att strukturträdet har innehåll. **veraPDF godkänner 1a med ett tomt
strukturträd**, så den kontrollen görs separat.

## Fonter

Fonterna är en fast lista i imagen, inget installeras eller byts ut i tysthet:

- DejaVu, Liberation (måttkompatibel med Arial, Times New Roman, Courier New),
  Carlito (Calibri) och Caladea (Cambria).
- Egna fonter laddas upp i [administrationen](#administration) och fungerar direkt, utan ny image.
  Fonter som alltid ska finnas kan också läggas i [`fonts/`](fonts/README.md) och byggas in.
- OpenType/CFF-fonter är avstängda via fontconfig (`docker/fonts.conf`), eftersom LibreOffice
  bäddar in dem på ett sätt som PDF/A-1 inte godtar.
- HTML-mallar kan skriva `font-family: Arial` eller `Calibri` och får den måttkompatibla fonten.
  Inbyggda alias finns under `converter.fonts.aliases`, egna läggs till i administrationen.

## Administration

Ett enkelt webbgränssnitt på `/admin` (användare `admin`, lösenord från `CONVERTER_ADMIN_PASSWORD`).
Utan lösenord är administrationen avstängd och `/admin` finns inte.

- **Testa filer:** släpp en eller flera Word-, Excel-, PowerPoint-, PDF- eller HTML-filer. De
  konverteras som via API:t och du ser om de klarar PDF/A-1a/1b och vad som bryts. Du ser också
  vilka fonter dokumentet begär (installerad, ersätts av alias, saknas) och vilka som hamnade i PDF:en.
  **Visuell avvikelse** jämför resultatet sida för sida med samma dokument utan PDF/A-rättningar.
  PDF/A-godkänt betyder inte att filen ser rätt ut, så kontrollera allt över 0,5 %. Flera filer
  sammanställs i en tabell som kan laddas ner som CSV.
- **Fonter:** ladda upp `.ttf`. Varje font kontrolleras: TrueType, och att licensen (OS/2 fsType)
  tillåter inbäddning. OpenType/CFF och fontsamlingar avvisas med en förklaring. Fonten blir
  tillgänglig för både LibreOffice och HTML-mallar, och LibreOffice startas om automatiskt.
- **Alias:** t.ex. `Garamond → EB Garamond` för fonter ni saknar licens för.
- **Export och import:** alla uppladdade fonter och alias som en zip, för att flytta mellan miljöer
  eller som säkerhetskopia. Import ersätter allt, men först efter att hela zip-filen kontrollerats.

Resurserna sparas i `CONVERTER_ASSETS_DIR` (`/data` i imagen). I Kubernetes är det en delad volym
(`deploy/k8s/pvc.yaml`, ReadWriteMany) som alla poddar läser. Poddar som inte tog emot ändringen
laddar om inom 30 sekunder. Under en omstart av LibreOffice väntar nya Office-anrop i några sekunder.

API:t bakom sidan (`/admin/api/...`) kan också skriptas. Ändrande anrop kräver huvudet
`X-Requested-With` som skydd mot CSRF:

```bash
curl -u admin:$PASS -H 'X-Requested-With: cli' -F files=@MinFont-Regular.ttf http://localhost:3000/admin/api/fonts
curl -u admin:$PASS -o resurser.zip http://localhost:3000/admin/api/export
```

## Skillnader mot Gotenberg

- HTML renderas med openhtmltopdf, inte Chromium. Det gäller CSS 2.1 med delar av CSS3: **inte
  flexbox, grid eller JavaScript**, och `opacity` och skuggor ignoreras. Det passar egna mallar
  och är det som gör PDF/A-1a möjligt. Sidhuvud och sidfot görs med CSS
  (`@page` + `position: running()`), inte med `header.html`/`footer.html`.
- Mallar kan bara läsa filer som skickats med i anropet. Nätverksanrop och övriga filsystemet är spärrade.
- Standardformat för HTML är A4 (Gotenberg: Letter) om varken mallen eller fälten anger något.
- Bara PDF/A-1a och PDF/A-1b. PDF/UA och övriga Gotenberg-fält ignoreras.

## Säkerhet och robusthet

- LibreOffice körs med en profil som blockerar länkade resurser i dokument (bilder via http eller
  `file:`) och stänger av makron. Annars kan en kundfil få tjänsten att anropa interna adresser
  eller bädda in lokala filer. `deploy/k8s/networkpolicy.yaml` stänger dessutom all utgående trafik.
- Bilder över 40 megapixel avvisas med en förklaring i stället för att packas upp.
- Utplattningen av bilder har ett minnes- och tidstak (10 s per dokument). Över taket läggs
  bilderna mot vitt.
- Två uppladdade filer med samma namn avvisas (`400`) i stället för att den ena tyst skriver över den andra.
- Dolda annoteringar (dolda fält, interna kommentarer) tas bort, eftersom PDF/A-1 annars kräver att de visas.
- Administrationens inloggning kontrolleras på vilken funktion anropet hamnar i, inte på URL:en.

## Kända begränsningar

- **Godtyckliga PDF:er kan inte bli 1a** om de saknar taggar. Ingen kan göra det tillförlitligt
  automatiskt, men 1b fungerar.
- **Verklig transparens** (t.ex. halvgenomskinliga former i Word, `opacity` i Chromium-PDF:er)
  avvisas. Bilder med alfakanal hanteras (se ovan), men halvgenomskinliga vektorobjekt plattas inte ut.
- Saknade fonter i kunders PDF:er bäddas inte in i efterhand, utan filen avvisas.
- Testfilerna är syntetiska. Riktiga kundfiler, särskilt PDF:er från Word, behövs i
  `src/test/resources/fixtures` innan driftsättning.

## Köra

```bash
docker build -t file-converter .
docker run -p 3000:3000 file-converter
```

Lokalt utan Docker krävs Java 21, LibreOffice och fonterna ovan:

```bash
mvn spring-boot:run
```

Kubernetes-manifest finns i `deploy/k8s/` (`kubectl apply -k deploy/k8s`). Skapa lösenordet för
administrationen separat: `kubectl create secret generic file-converter-admin --from-literal=password=...`.

### Konfiguration

Allt i `application.yml` kan sättas med miljövariabler, t.ex.:

| Variabel | Standard | |
|---|---|---|
| `CONVERTER_OFFICE_POOL_SIZE` | `2` | LibreOffice-processer = samtidiga Office-konverteringar |
| `CONVERTER_OFFICE_TASK_TIMEOUT` | `120s` | |
| `CONVERTER_OFFICE_MAX_TASKS_PER_PROCESS` | `200` | Processen startas om efter så många jobb |
| `CONVERTER_PDFA_STRICT` | `true` | `false` = returnera även underkända filer |
| `CONVERTER_ADMIN_PASSWORD` | tomt | Aktiverar `/admin` |
| `CONVERTER_ASSETS_DIR` | `/data` (imagen) | Uppladdade fonter och alias |
| `CONVERTER_ASSETS_POLL_INTERVAL` | `30s` | Hur ofta andra poddars ändringar plockas upp |
| `CONVERTER_LIMITS_MAX_CONCURRENT` | antal processorer | Samtidiga konverteringar per instans. Fler köar |
| `CONVERTER_LIMITS_QUEUE_TIMEOUT` | `60s` | Därefter `503` med `Retry-After` |
| `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` | `100MB` | |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `100MB` | |

## Tester

```bash
mvn verify   # kräver LibreOffice och fonterna; integrationstesterna hoppas över utan LibreOffice
```

Varje PDF/A-svar i integrationstesterna valideras om med veraPDF och kontrolleras för innehåll i
strukturträdet. Testfilerna skapas med `scripts/make_fixtures.py` (kräver python-docx, Pillow och
fonttools). Testfonterna är omdöpta delmängder av DejaVu Sans.

## Licenser

LibreOffice (MPL 2.0), PDFBox (Apache 2.0), veraPDF (MPL 2.0 / GPL 3, dubbellicens),
openhtmltopdf (LGPL 2.1), jsoup (MIT), JODConverter (Apache 2.0), Spring Boot (Apache 2.0).
Ingen AGPL. Ghostscript används inte.

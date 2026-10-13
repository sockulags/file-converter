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
| Färgprofil (sRGB OutputIntent) | Redan signerade filer (signaturen skulle brytas) |
| CIDSet för fontdelmängder (saknas i Word/Chromium-PDF) | Fonter som inte är inbäddade |
| Bilder med alfakanal (läggs mot vit bakgrund), JPEG2000 | Verklig transparens (`opacity`, blandningslägen) |
| Utskriftsflaggor och actions på annoteringar, JavaScript, bifogade filer | För 1a: avsaknad av taggstruktur |
| xref-strömmar (PDF/A-1 bygger på PDF 1.4) | |

För PDF/A-1a krävs dessutom att strukturträdet har innehåll. **veraPDF godkänner 1a med ett tomt
strukturträd**, så den kontrollen görs separat.

## Fonter

Fonterna är en fast lista i imagen, inget installeras eller byts ut i tysthet:

- DejaVu, Liberation (måttkompatibel med Arial, Times New Roman, Courier New),
  Carlito (Calibri) och Caladea (Cambria).
- Egna `.ttf`-filer läggs i [`fonts/`](fonts/README.md).
- OpenType/CFF-fonter är avstängda via fontconfig (`docker/fonts.conf`), eftersom LibreOffice
  bäddar in dem på ett sätt som PDF/A-1 inte godtar.
- HTML-mallar kan skriva `font-family: Arial` eller `Calibri` och får den måttkompatibla fonten.
  Alias ställs in under `converter.fonts.aliases`.

## Skillnader mot Gotenberg

- HTML renderas med openhtmltopdf, inte Chromium. Det gäller CSS 2.1 med delar av CSS3: **inte
  flexbox, grid eller JavaScript**, och `opacity` och skuggor ignoreras. Det passar egna mallar
  och är det som gör PDF/A-1a möjligt. Sidhuvud och sidfot görs med CSS
  (`@page` + `position: running()`), inte med `header.html`/`footer.html`.
- Mallar kan bara läsa filer som skickats med i anropet. Nätverksanrop och övriga filsystemet är spärrade.
- Standardformat för HTML är A4 (Gotenberg: Letter) om varken mallen eller fälten anger något.
- Bara PDF/A-1a och PDF/A-1b. PDF/UA och övriga Gotenberg-fält ignoreras.

## Kända begränsningar

- **Godtyckliga PDF:er kan inte bli 1a** om de saknar taggar. Ingen kan göra det tillförlitligt
  automatiskt, men 1b fungerar.
- **Verklig transparens** (t.ex. halvgenomskinliga former i Word, `opacity` i Chromium-PDF:er)
  avvisas. Utplattning utan Ghostscript är inte byggd ännu.
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

Kubernetes-manifest finns i `deploy/k8s/` (`kubectl apply -k deploy/k8s`).

### Konfiguration

Allt i `application.yml` kan sättas med miljövariabler, t.ex.:

| Variabel | Standard | |
|---|---|---|
| `CONVERTER_OFFICE_POOL_SIZE` | `2` | LibreOffice-processer = samtidiga Office-konverteringar |
| `CONVERTER_OFFICE_TASK_TIMEOUT` | `120s` | |
| `CONVERTER_OFFICE_MAX_TASKS_PER_PROCESS` | `200` | Processen startas om efter så många jobb |
| `CONVERTER_PDFA_STRICT` | `true` | `false` = returnera även underkända filer |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `100MB` | |

## Tester

```bash
mvn verify   # kräver LibreOffice och fonterna; integrationstesterna hoppas över utan LibreOffice
```

Varje PDF/A-svar i integrationstesterna valideras om med veraPDF och kontrolleras för innehåll i
strukturträdet. Testfilerna skapas med `scripts/make_fixtures.py`.

## Licenser

LibreOffice (MPL 2.0), PDFBox (Apache 2.0), veraPDF (MPL 2.0 / GPL 3, dubbellicens),
openhtmltopdf (LGPL 2.1), jsoup (MIT), JODConverter (Apache 2.0), Spring Boot (Apache 2.0).
Ingen AGPL. Ghostscript används inte.

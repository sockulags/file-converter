# file-converter

Konverteringstjänst med samma API som Gotenberg 8 för routerna nedan, men med **validerad PDF/A**:
1a, 1b, 2a, 2u, 2b, 3a, 3u och 3b (Gotenbergs `PDF/A-1b`, `PDF/A-2b` och `PDF/A-3b` och de taggade
a-nivåerna). Byggd för att ersätta Gotenberg där fonter, grafik och PDF/A har strulat.

| Del | Bygger på | Skillnad |
|---|---|---|
| PDF/A-1 | PDF 1.4 | Ingen transparens. Bilder med alfakanal plattas ut, verklig transparens avvisas eller rapporteras |
| PDF/A-2 | PDF 1.7 | Tillåter transparens, ICC v4, JPEG2000 och objektströmmar. Innehållet lämnas orört |
| PDF/A-3 | PDF 1.7 | Som PDF/A-2. Bifogade filer stöds ännu inte och tas bort |

Inom en del uppfyller a (taggad, tillgänglig) även u (Unicode för all text), som uppfyller b.

Alla filer som begärs som PDF/A normaliseras och valideras med [veraPDF](https://verapdf.org)
innan de skickas tillbaka.

**Två lägen** (`CONVERTER_PDFA_STRICT`, eller per anrop med fältet `pdfaStrict`):

- **Bästa försök** (standard, som Gotenberg): allt som går rättas och filen returneras alltid.
  Svaret säger ärligt hur det gick: `X-Pdfa-Compliant: true|false`, `X-Pdfa-Level` (nivån filen
  faktiskt märktes med) och `X-Pdfa-Problems` (veraPDF-regler och egna orsakskoder som återstår).
  En fil som inte kan bli t.ex. 1a eller 2a, för att den saknar taggar, märks som 1b respektive 2b i
  stället för att påstå något den inte är.
- **Strikt**: en fil som inte klarar valideringen avvisas med `422` och en förklaring.

Krypterade och redan signerade filer samt jättebilder avvisas i båda lägena.

## Routes

| Route | Indata | Motor |
|---|---|---|
| `POST /forms/libreoffice/convert` | Office-filer (docx, xlsx, pptx, odt, rtf …) | LibreOffice via JODConverter |
| `POST /forms/chromium/convert/html` | `index.html` + resurser (bilder, css, js, ttf) | openhtmltopdf eller Chromium, efter mallen (se nedan) |
| `POST /forms/pdfengines/convert` | PDF-filer | PDFBox (normalisering) |
| `POST /forms/pdfengines/merge` | PDF-filer, slås ihop i bokstavsordning | PDFBox |
| `GET /health` | – | `503` tills LibreOffice är igång. `details` visar `libreoffice` och `chromium` |

Port `3000`, precis som Gotenberg. Mätvärden på port `9090`.

### Gotenberg-kompatibilitet

Fälten följer Gotenberg 8. Varje fält hör till en av tre grupper:
**stöds**, **ignoreras** (saknar betydelse här och listas i svarshuvudet
`X-Converter-Ignored-Fields`, så att inget försvinner i tysthet) eller **avvisas** med `400`
när resultatet inte kan bli det klienten väntar sig.

| Route | Stöds | Ignoreras | Avvisas |
|---|---|---|---|
| alla | `pdfa`, `metadata` (JSON), `flatten`, huvudena `Gotenberg-Output-Filename` och `Gotenberg-Trace` | okända fält | `pdfua=true`, `split*`, `downloadFrom`, `Gotenberg-Webhook-*` |
| `libreoffice/convert` | `merge`, `landscape`, `nativePageRanges`, `password`, `exportFormFields`, `allowDuplicateFieldNames`, `exportBookmarks`, `exportBookmarksToPdfDestination`, `exportPlaceholders`, `exportNotes`, `exportNotesPages`, `exportOnlyNotesPages`, `exportNotesInMargin`, `convertOooTargetToPdfTarget`, `exportLinksRelativeFsys`, `exportHiddenSlides`, `skipEmptyPages`, `singlePageSheets`, `losslessImageCompression`, `quality`, `reduceImageResolution`, `maxImageResolution`, `addOriginalDocumentAsStream` (ej med PDF/A) | | |
| `chromium/convert/html`, båda motorerna | `header.html`, `footer.html`, `paperWidth`, `paperHeight`, `margin*`, `landscape`, `preferCssPageSize`, `printBackground`, `generateDocumentOutline`, `nativePageRanges`, `converterEngine` (tillägg) | `cookies`, `extraHttpHeaders` (mallar når inte nätverket) | |
| `chromium/convert/html`, Chromium | `waitDelay` (tak `CONVERTER_CHROMIUM_MAX_WAIT_DELAY`), `waitForExpression`, `waitForSelector`, `emulatedMediaType`, `emulatedMediaFeatures`, `userAgent`, `omitBackground`, `scale`, `singlePage`, `generateTaggedPdf`, `failOnConsoleExceptions`, `failOnResourceLoadingFailed`, `failOnResourceHttpStatusCodes`, `skipNetworkIdleEvent` | `failOnHttpStatusCodes`, `ignoreResourceHttpStatusDomains` (sidan läses aldrig över http) | |
| `chromium/convert/html`, openhtmltopdf | | Chromium-fälten ovan | `singlePage`, `scale` ≠ 1, `emulatedMediaType=screen` (bara med `converterEngine=openhtmltopdf`; annars väljs Chromium), `nativePageRanges` med en a-nivå |
| `pdfengines/convert`, `merge` | `merge`-ordning i bokstavsordning | | |

Tal utan enhet i pappers- och marginalfält är tum, som i Gotenberg. Annars `mm`, `cm`, `in`, `pt`, `px`.

### HTML-mallar: openhtmltopdf eller Chromium

Varje mall renderas med den motor som klarar den. Svaret säger vilken och varför
(`X-Converter-Engine`, `X-Converter-Engine-Reason`):

| Ordning | Motor | Orsak |
|---|---|---|
| 1. Fältet `converterEngine=chromium\|openhtmltopdf` | den valda | `vald` |
| 2. Fält som bara fungerar i en webbläsare: `waitDelay`, `waitFor*`, `emulatedMediaFeatures`, `failOnConsoleExceptions`, `singlePage`, `omitBackground`, `generateTaggedPdf`, `scale` ≠ 1, `emulatedMediaType=screen` | Chromium | `falt` |
| 3. Mallen (index.html, header.html, footer.html och alla .css): `<script>`, `on*=`, `javascript:` | Chromium | `script` |
| `display: flex`, `display: grid`/`grid-template` | Chromium | `flexbox`, `grid` |
| `position: sticky`, `var(--…)`, `@supports`, `@container`, `transform`, `filter`, `box-shadow`, `opacity` < 1, kolumner | Chromium | `css3` |
| `<svg>`, `.svg`-bilder, `<canvas>`, `<iframe>` | Chromium | `svg`, `canvas`, `iframe` |
| 4. Annars | openhtmltopdf | `standard` |

- **openhtmltopdf** ger taggad PDF/A direkt från HTML-strukturen och är förstahandsvalet för egna mallar.
  Utan fält blir sidan A4 och mallens `@page` gäller.
- **Chromium** (Chrome for Testing, headless) renderar som Gotenberg och med Gotenbergs standardvärden:
  Letter, marginaler 0,39 tum, `preferCssPageSize=false`, `printBackground=false`, och `header.html`/
  `footer.html` som Chromiums sidhuvud och sidfot. Med PDF/A begärd skapar Chromium en taggad PDF.
  En b-nivå i del 2 eller 3 märks då med a-nivån om filen klarar den (`X-Pdfa-Level: PDF/A-2a` när
  `PDF/A-2b` begärdes), annars den begärda nivån. Chromium-PDF:er har ofta transparens, så PDF/A-2b
  är det naturliga valet. `failOn*` ger `409` som i Gotenberg.

Mallen öppnas i Chromium under en påhittad adress, och varje begäran från sidan besvaras från
filerna i anropet. Allt annat stoppas: andra adresser, `file:` och nätverket (alla värdnamn pekar
på ingenting). Varje anrop får en egen inkognitokontext. Fonterna och aliasen är desamma som för
LibreOffice och openhtmltopdf.

Detaljer:
- **`header.html`/`footer.html`** blir löpande sidhuvud och sidfot i sidans marginaler.
  Klasserna `pageNumber`, `totalPages`, `title` och `date` fylls i som i Chromium. Mallen
  behöver marginaler som rymmer dem, precis som i Gotenberg.
- **`printBackground`** är `false` som standard, precis som i Chromium. Bakgrundsfärger och
  bakgrundsbilder skrivs då inte ut, utom om mallen (eller dess CSS) använder `print-color-adjust`.
- **`metadata`** skrivs både i dokumentinformationen och i PDF/A-metadatan (XMP).
- **`landscape`** i LibreOffice gör alla sidformat i dokumentet liggande.

Tillägg: `pdfaStrict=false` returnerar filen även om valideringen fallerar.
Flera filer utan `merge` ger en zip, som i Gotenberg.

### Svar

- `X-Pdfa-Level: PDF/A-1a` och `X-Pdfa-Compliant: true|false` när PDF/A har begärts. En zip med
  filer som fått olika nivåer listar dem kommaseparerade.
- `Gotenberg-Trace`: anropets spårnings-id. Klientens eget id används om det skickas med.
- Fel som `application/problem+json`. `reason` är en maskinläsbar orsak, `violations` anger exakt
  vad som stoppade PDF/A, och `traceId` kopplar felet till loggarna:

```json
{
  "status": 422,
  "detail": "PDF-filen kan inte göras om till PDF/A-1 utan att utseendet ändras.",
  "reason": "TRANSPARENCY",
  "violations": ["Transparens på sida 1 (tillåts inte i PDF/A-1)."],
  "traceId": "arende-4711"
}
```

Orsaker: `ENCRYPTED`, `SIGNED`, `UNTAGGED`, `TRANSPARENCY`, `MISSING_FONT`, `IMAGE_TOO_LARGE`,
`PDFA_INVALID`, `UNREADABLE_PDF`, `LIBREOFFICE_FAILED`, `LIBREOFFICE_TIMEOUT`, `LIBREOFFICE_CRASHED`, `BUSY`,
`MERGE_FAILED`, `UNSUPPORTED_FORMAT`, `UNSUPPORTED_OPTION`, `UNSUPPORTED_ROUTE`, `DUPLICATE_FILENAME`,
`BAD_REQUEST`, `TOO_LARGE`, `UNAVAILABLE`, `CHROMIUM_TIMEOUT`, `CHROMIUM_CRASHED`, `CHROMIUM_FAILED`,
`CONSOLE_EXCEPTION`, `RESOURCE_FAILED`, `INTERNT_FEL`.

### Exempel

```bash
curl -F files=@beslut.docx -F pdfa=PDF/A-1a http://localhost:3000/forms/libreoffice/convert -o beslut.pdf
curl -F files=@mall.html\;filename=index.html -F files=@logo.png -F pdfa=PDF/A-1a \
     http://localhost:3000/forms/chromium/convert/html -o brev.pdf
curl -F files=@kund.pdf -F pdfa=PDF/A-1a http://localhost:3000/forms/pdfengines/convert -o kund-pdfa.pdf
```

## Så blir filerna PDF/A

**Office:** LibreOffice exporterar taggad PDF/A-2 (PDF/A-3 när del 3 begärs). För PDF/A-1 gör
normaliseringen om den till PDF/A-1. Imagen bygger på Ubuntu 26.04 med LibreOffice 26.2. Testat med `.docx`, `.doc`,
`.odt`, `.pptx` och `.xlsx`:

| Format | Resultat |
|---|---|
| Word (`.docx`, `.doc`), `.odt`, PowerPoint (`.pptx`), kalkylark (`.xlsx`) | PDF/A-1a med taggar |
| Bilder med halvgenomskinliga former eller skuggor | PDF/A-1b: LibreOffice exporteras om i PDF/A-1-läge, som plattar ut transparensen men tar bort taggarna |

Begärs 1a för dem märks filen 1b i bästa försök-läget (`X-Pdfa-Level: PDF/A-1b`). LibreOffice:s eget PDF/A-1-läge används inte, eftersom det kastar hela
taggstrukturen så fort dokumentet innehåller transparens, t.ex. en PNG med alfakanal.
Med PDF/A-2 och 3 behövs ingen utplattning: samma bilder blir taggad 2a med transparensen kvar.

Listpunkter som Word skriver med fonten Symbol pekar på Unicodes privata område (t.ex. U+F0B7), vilket
PDF/A-2a och 3a inte godtar. Mappningen pekas om till det riktiga tecknet (•), som också blir sökbart.

**HTML-mallar:** openhtmltopdf skapar taggad PDF/A direkt från HTML-strukturen (rubriker, listor,
tabeller, `alt`-texter). `lang` på `<html>` följer med.

**Befintliga PDF:er** (`pdfengines/convert`, `merge`): normaliseringen i `PdfaNormalizer` rättar
utan att rendera om innehållet:

| Rättas | Avvisas i strikt läge (lämnas och rapporteras i bästa försök) |
|---|---|
| XMP-metadata och datum (synkas med Info) | Kryptering och lösenord |
| CharSet för Type 1-delmängder (OpenType/CFF-fonter) | |
| CIDSet räknas alltid om, eftersom Word m.fl. skriver ofullständiga | |
| CMYK i en RGB-fil (och tvärtom): `DefaultCMYK` med FOGRA39 anger hur färgerna tolkas, så de behålls | |
| ICC-profiler i version 4 byts mot version 2, och flera olika utdataprofiler blir en | |
| Färgprofil (sRGB OutputIntent) | Redan signerade filer (signaturen skulle brytas) |
| CIDSet för fontdelmängder (saknas i Word/Chromium-PDF) | Fonter som inte är inbäddade |
| Bilder med alfakanal (läggs mot den bakgrund de ligger på, se nedan), JPEG2000 | Verklig transparens (`opacity`, blandningslägen). Bästa försök: lämnas, och filen märks som ej godkänd |
| Utskriftsflaggor och actions på annoteringar, JavaScript, bifogade filer | För 1a: avsaknad av taggstruktur (bästa försök: blir 1b) |
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
  konverteras som via API:t och du ser om de klarar vald PDF/A-nivå och vad som bryts. Du ser också
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
(`deploy/k8s/base/pvc.yaml`, ReadWriteMany) som alla poddar läser. Poddar som inte tog emot ändringen
laddar om inom 30 sekunder. Under en omstart av LibreOffice väntar nya Office-anrop i några sekunder.

API:t bakom sidan (`/admin/api/...`) kan också skriptas. Ändrande anrop kräver huvudet
`X-Requested-With` som skydd mot CSRF:

```bash
curl -u admin:$PASS -H 'X-Requested-With: cli' -F files=@MinFont-Regular.ttf http://localhost:3000/admin/api/fonts
curl -u admin:$PASS -o resurser.zip http://localhost:3000/admin/api/export
```

## Övervakning

Mätvärden finns på en egen port (`9090`) i Prometheus-format: `/actuator/prometheus`. Podden har
annoteringar för Prometheus.

| Mätvärde | Taggar | |
|---|---|---|
| `converter_conversions_total` | `route`, `pdfa`, `outcome` | `ok`, `underkand`, `avvisad`, `fel`. `pdfa` är en giltig nivå, `ingen` eller `ogiltig` |
| `converter_rejections_total` | `reason` | Varför filer avvisas, se orsakerna ovan |
| `converter_pdfa_violations_total` | `rule` | Vilka PDF/A-regler (veraPDF) som bryts |
| `converter_conversion_duration_seconds` | `route`, `outcome` | Histogram |
| `converter_busy_total` | | Anrop som fick `503` för att instansen var full |
| `converter_request_fields_total` | `route`, `field`, `handling` | Fält och `Gotenberg-*`-huvuden klienterna skickar: `stods`, `ignoreras`, `avvisas` |
| `converter_request_files_total` | `route`, `extension` | Filtyper klienterna skickar |
| `converter_unknown_routes_total` | `route`, `method` | Anrop till Gotenberg-routes som inte finns här (besvaras med `404`) |
| `converter_engine_selected_total` | `engine`, `reason` | Vilken motor HTML-mallar renderas med, och varför |

Varje konvertering ger en loggrad (`route`, `pdfa`, `utfall`, `orsak`, `status`, `ms`, `filer`,
`ej_stod`, `motor`) med spårnings-id. `ej_stod` listar fält som ignorerades eller avvisades, med klientens egna namn. JSON-loggar för t.ex. Loki eller Elastic: `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.

### Vad klienterna skickar

Inför och efter bytet från Gotenberg visar mätvärdena ovan vad klienterna faktiskt använder, utan att
någon behöver läsa deras kod. Fältnamn och routes är en fast lista med Gotenberg 8:s namn. Okända
namn räknas som `okant` respektive `okand`, så att klienter inte kan skapa nya serier i Prometheus.
Klientens egna namn står på loggraden. Exempel i PromQL:

```promql
# Fält som klienterna skickar men som inte stöds, per route
sum by (route, field, handling) (increase(converter_request_fields_total{handling!="stods"}[7d]))
# Routes som saknas här
sum by (route, method) (increase(converter_unknown_routes_total[7d]))
```

Fliken **Statistik** i administrationen visar samma sak för den instans du är inloggad mot, plus de
100 senaste avvisade eller misslyckade anropen med filnamn, orsak och spårnings-id. Körningar i
testbänken räknas som en egen route, `testbank`. Siffrorna ligger i minnet: de nollställs när podden
startas om, och med flera repliker ser du bara den pod som svarade. För hela klustret över tid, använd
Prometheus-måtten ovan.

## Skillnader mot Gotenberg

- HTML-mallar utan Chromium-behov renderas med openhtmltopdf (CSS 2.1 med delar av CSS3), som ger
  taggad PDF/A-1a direkt. Där är standardformatet A4 och mallar kan använda `@page` +
  `position: running()`. Mallar med flexbox, grid, JavaScript m.m. renderas med Chromium som i Gotenberg.
- Mallar kan bara läsa filer som skickats med i anropet. Nätverksanrop och övriga filsystemet är
  spärrade, så `cookies` och `extraHttpHeaders` har ingen verkan.
- Routes för URL-konvertering, skärmdumpar och split finns inte.
- PDF/A-1a och a-nivåerna i del 2 och 3 utöver Gotenbergs b-nivåer. Bifogade filer i PDF/A-3
  (`addOriginalDocumentAsStream`, e-faktura) och PDF/UA stöds inte ännu.

## Säkerhet och robusthet

- LibreOffice körs med en profil som blockerar länkade resurser i dokument (bilder via http eller
  `file:`) och stänger av makron. Annars kan en kundfil få tjänsten att anropa interna adresser
  eller bädda in lokala filer. `deploy/k8s/base/networkpolicy.yaml` stänger dessutom all utgående trafik och
  släpper bara in klienter och Prometheus.
- Bilder över 40 megapixel avvisas med en förklaring i stället för att packas upp.
- Utplattningen av bilder har ett minnes- och tidstak (10 s per dokument). Över taket läggs
  bilderna mot vitt.
- Bara fel i själva dokumentet ger `422 LIBREOFFICE_FAILED`. Fel som beror på last eller miljö ger `503`,
  så att klienten kan försöka igen: `BUSY` (alla LibreOffice-processer upptagna, med `Retry-After`),
  `LIBREOFFICE_TIMEOUT` (`CONVERTER_OFFICE_TASK_TIMEOUT` passerad, som i Gotenberg) och
  `LIBREOFFICE_CRASHED`. Kraschar LibreOffice under ett jobb görs ett nytt försök med en ny process
  innan anropet ger upp. I mätvärdena räknas `503` som utfallet `fel`, inte `avvisad`.
- Chromium körs utan egen sandlåda (`CONVERTER_CHROMIUM_SANDBOX=false`), eftersom den kräver user
  namespaces som containrar sällan har. Isoleringen sköts av containern (icke-root, seccomp, inget
  nätverk) och av att varje begäran från mallen besvaras eller stoppas av tjänsten. Varje rendering
  har en tidsgräns (`CONVERTER_CHROMIUM_JOB_TIMEOUT`), och webbläsaren startas om efter
  `CONVERTER_CHROMIUM_RESTART_AFTER_JOBS` renderingar eller om den kraschar.
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

Lokalt utan Docker krävs Java 21, LibreOffice (helst 26.2, som i imagen) och fonterna ovan:

```bash
mvn spring-boot:run
```

### Kubernetes och Flux

Manifesten ligger i `deploy/k8s/`: en gemensam `base/` och en overlay per miljö (`overlays/test`,
`overlays/prod`). Overlayn sätter namespace, image och det som skiljer miljöerna åt. Registry,
namespace och lagringsklass i overlays är exempel som byts mot era egna.

```bash
kubectl apply -k deploy/k8s/overlays/test
```

Med Flux pekar en `Kustomization` per miljö på sin overlay, se `deploy/flux/file-converter.yaml`.
Produktion väntar där på att testmiljön är frisk (`dependsOn`).

- **Image:** CI publicerar imagen när repo-variabeln `REGISTRY` är satt, med hemligheterna
  `REGISTRY_USERNAME` och `REGISTRY_PASSWORD`. Taggar: `main`, `sha-<commit>` och för git-taggar som
  `v1.2.3` även `1.2.3` och `1.2`. SBOM och provenance följer med, så att registryt kan skanna imagen.
  Test följer `main`. Produktion låses till en release, och ett byte görs som en ändring av `newTag`
  i `overlays/prod`.
- **Hemligheter:** lösenordet till administrationen (`file-converter-admin`) och hämtningshemligheten
  till registryt (`file-converter-registry`) checkas inte in i klartext. Skapa dem i klustret, eller
  kryptera dem med SOPS och låt Flux avkoda dem:
  `kubectl create secret generic file-converter-admin --from-literal=password=...`
- **Vilka som når tjänsten:** NetworkPolicy släpper bara in namespaces med etiketten
  `file-converter/klient=true` (port 3000) och `file-converter/metrics=true` (port 9090, Prometheus).
  Märk klienternas namespaces innan bytet, annars får de inget svar:
  `kubectl label namespace <namespace> file-converter/klient=true`

### Konfiguration

Allt i `application.yml` kan sättas med miljövariabler, t.ex.:

| Variabel | Standard | |
|---|---|---|
| `CONVERTER_OFFICE_POOL_SIZE` | `2` | LibreOffice-processer = samtidiga Office-konverteringar |
| `CONVERTER_OFFICE_TASK_TIMEOUT` | `120s` | |
| `CONVERTER_OFFICE_MAX_TASKS_PER_PROCESS` | `200` | Processen startas om efter så många jobb |
| `CONVERTER_PDFA_STRICT` | `false` | `false` = bästa försök (som Gotenberg), `true` = avvisa med 422 |
| `CONVERTER_ADMIN_PASSWORD` | tomt | Aktiverar `/admin` |
| `CONVERTER_ASSETS_DIR` | `/data` (imagen) | Uppladdade fonter och alias |
| `CONVERTER_ASSETS_POLL_INTERVAL` | `30s` | Hur ofta andra poddars ändringar plockas upp |
| `CONVERTER_LIMITS_MAX_CONCURRENT` | antal processorer | Samtidiga konverteringar per instans. Fler köar |
| `CONVERTER_LIMITS_QUEUE_TIMEOUT` | `60s` | Därefter `503` med `Retry-After` |
| `CONVERTER_CHROMIUM_ENABLED` | `true` | Av = mallar som kräver Chromium får `503 UNAVAILABLE` |
| `CONVERTER_CHROMIUM_PATH` | tomt | `chrome-headless-shell` eller `chrome`. Tomt = `/opt/chrome-headless-shell` m.fl. kända platser |
| `CONVERTER_CHROMIUM_MAX_CONCURRENT` | `2` | Samtidiga Chromium-sidor. Varje sida tar cirka 150–300 MB |
| `CONVERTER_CHROMIUM_QUEUE_TIMEOUT` | `30s` | Därefter `503 BUSY` med `Retry-After` |
| `CONVERTER_CHROMIUM_JOB_TIMEOUT` | `60s` | Hel rendering inklusive väntan. Därefter `503 CHROMIUM_TIMEOUT` |
| `CONVERTER_CHROMIUM_MAX_WAIT_DELAY` | `30s` | Tak för `waitDelay` |
| `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` | `100MB` | |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `100MB` | |

## Tester

```bash
mvn verify   # kräver LibreOffice och fonterna; integrationstesterna hoppas över utan LibreOffice
```

Chromium-testerna körs när en Chromium finns (`CONVERTER_CHROMIUM_PATH`, `/usr/bin/google-chrome`
m.fl., eller Playwrights `chrome-headless-shell`), annars hoppas de över.

Imagen innehåller Chrome for Testing (`CHROME_VERSION` i `Dockerfile`), som uppdateras för hand.
Chromium och dess bibliotek gör imagen cirka 450 MB större.

Varje PDF/A-svar i integrationstesterna valideras om med veraPDF och kontrolleras för innehåll i
strukturträdet. Testfilerna skapas med `scripts/make_fixtures.py` (kräver python-docx, Pillow och
fonttools). Testfonterna är omdöpta delmängder av DejaVu Sans.

## Licenser

LibreOffice (MPL 2.0), PDFBox (Apache 2.0), veraPDF (MPL 2.0 / GPL 3, dubbellicens),
openhtmltopdf (LGPL 2.1), jsoup (MIT), JODConverter (Apache 2.0), Spring Boot (Apache 2.0),
Chrome for Testing (BSD m.fl., Chromium-licenserna).
Ingen AGPL. Ghostscript används inte.
CMYK-profilen `FOGRA39L_coated.icc` kommer från colord och är fri (CC0).

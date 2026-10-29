# Gap-analys inför produktion

Analysen bygger på repot (commit `fec62b6`, ovanpå `9f5c756`) och ägarens svar 2026-10-28. Alla belägg anges som sökväg:rad.

## Sammanfattning

**Läge.** file-converter ska ersätta Gotenberg i produktion med ett direkt byte, utan skuggkörning men med möjlighet att rulla tillbaka. Tjänsten klarar idag fyra routes och PDF/A-1a/1b. HTML renderas bara med openhtmltopdf. CI bygger imagen men publicerar den inte. Manifesten är en enda kustomization utan overlays, larm eller dashboard.

**Beslut.** Ägaren har bestämt fem saker:
- Chromium läggs till som komplement till openhtmltopdf, och motorn väljs automatiskt utifrån mallen.
- PDF/A-2b räcker på Chromium-vägen.
- Imagen publiceras i Harbor och driftsätts med GitOps.
- Bara interna klienter använder tjänsten, så ingen API-autentisering behövs.
- Volymen är 1 000–50 000 konverteringar per dag.

Uppgraderingen till Ubuntu 26.04 och LibreOffice 26.2 görs i samma PR (`fec62b6`). Alla 76 tester går igenom, och kalkylark blir nu taggad PDF/A-1a i stället för 1b.

**De viktigaste riskerna:**
1. **Ingen vet vilka fält, routes och huvuden klienterna skickar, och tjänsten kan inte mäta det.** Vid ett direkt byte blir det tysta fel eller 400.
2. **Gotenbergs vanliga PDF/A-värden avvisas med 400.** Gotenberg tar emot `PDF/A-2b` och `PDF/A-3b`, men här finns bara 1a/1b.
3. **Mallar med flex, grid eller JS renderas fel.** Chromium saknas, och standardformatet skiljer sig från Gotenbergs (A4 här, Letter och 0.39in där).
4. **Överlast ser ut som trasiga filer.** När LibreOffice-poolen är full eller en tidsgräns slår till blir svaret `422`. Samtidighetsgränsen följer nodens CPU-antal.
5. **Skydd och drift saknas.** Det finns inga ingress-regler, ingen publicering till Harbor, inga overlays, ingen skrapning av mätvärden, inga larm och ingen runbook. Rollbackbeslutet saknar därför underlag.

## Beslut

| Område | Beslut | Följd i analysen |
|---|---|---|
| Övergång | Direkt byte från Gotenberg, med rollback men utan skuggkörning | Kompatibilitet, inventering av fält och larm måste vara klara före bytet |
| HTML-motor | openhtmltopdf behålls för egna mallar som ska bli taggad PDF/A-1a. Headless Chromium läggs till för flex, grid och JS | Ny motor, sandlåda och fältstöd |
| Motorval | Sker automatiskt utifrån mallen, och motorn kan tvingas per anrop | Signaler, svarshuvudet `X-Converter-Engine` och tagg i mätvärden och loggar |
| PDF/A på Chromium-vägen | PDF/A-2b räcker. Märks 2a om filen blir taggad och godkänd, annars 2b | Kräver PDF/A-2 i hela PDF/A-kedjan |
| Fler standarder | PDF/A-2b/2u/2a, PDF/A-3 och PDF/UA behövs | 2b/3b blockerar, 3 med bilagor och PDF/UA kommer efter bytet |
| Release | Imagen publiceras i Harbor med semver- och sha-taggar, skanning och SBOM | Nytt publiceringsjobb i CI |
| Driftsättning | GitOps med Argo eller Flux | Bas och overlays, hemligheter, pull-secret |
| Åtkomst | Bara interna klienter. NetworkPolicy och ingress-regler räcker, ingen API-autentisering | Ingress-regler måste finnas i manifesten |
| Volym | 1 000–50 000 per dag, med toppar | Lasttest och kapacitetsguide |
| Plattform | Prometheus/Grafana, Loki/Elastic | ServiceMonitor eller PodMonitor, PrometheusRule, dashboard, ECS-loggar |
| Basimage och LibreOffice | Ubuntu 26.04 (`eclipse-temurin:25-jre-resolute`, `ubuntu-26.04` i CI) och LibreOffice 26.2 från Ubuntu, i samma PR | Görs redan. Kvar är versionsspårning och att låsa versionen, se F1 |
| Ej behövt | Webhook, asynkront API, URL-konvertering, skärmdumpar och split | Fortsätter att avvisas |

## Blockerar produktion

### B1. Inget sätt att mäta vilka fält, huvuden och routes klienterna skickar
- **Varför:** Ingen vet vad klienterna skickar, och bytet görs utan skuggkörning.
  - Ignorerade fält syns bara i svarshuvudet `X-Converter-Ignored-Fields`. De kommer inte med i loggraden eller mätvärdena.
  - Avvisningar räknas bara per orsak (`UNSUPPORTED_OPTION`), inte per fält.
  - Gotenberg-routes som saknas här (markdown, flatten, metadata, encrypt, `/version` …) ger Springs vanliga 404 och registreras inte alls.
  - Taggen `pdfa` tar klientens råa värde innan det validerats. Det ger obegränsad kardinalitet i Prometheus.
  - Multipart- och storleksfel anropar inte `rejected()`.
- **Belägg:**
  - `src/main/java/se/fileconverter/api/GotenbergOptions.java:88-99`, `:120-122`
  - `src/main/java/se/fileconverter/api/ConversionController.java:183-187` (rått `pdfa` före `PdfaLevel.parse` på `:85/:107/:130/:146`), `:196-198`
  - `src/main/java/se/fileconverter/observability/ConversionRecord.java:35`
  - `src/main/java/se/fileconverter/observability/ConversionTelemetry.java:69`, `:264-286`
  - `src/main/java/se/fileconverter/observability/ConversionTracingFilter.java:60-62`
  - `src/main/java/se/fileconverter/api/ErrorHandler.java:107-115`
- **Förslag:**
  1. Räkna `converter_request_fields_total{route,field,handling=stods|ignoreras|avvisas}` i `GotenbergOptions.validate`. Fältnamnet tas från en fast vitlista med alla kända Gotenberg 8-fält, och allt annat blir `okant`. Räkna huvudena `Gotenberg-*`, antalet filer och filändelserna på samma sätt.
  2. Normalisera `pdfa` i `ConversionRecord.start` till en känd etikett eller `ogiltig`.
  3. Lägg till en catch-all för `/forms/**` och `GET /version`. Den räknar `converter_unknown_route_total{route}` från en vitlista och svarar 404.
  4. Skriv ignorerade och avvisade fältnamn på loggraden.
  5. Låt `ErrorHandler.tooLarge/notMultipart` anropa `rejected()`.

  Komplettera med att söka i klienternas kod efter `/forms/` och fältnamn, och med ingress- eller Gotenberg-loggar. Stäm sedan av mot `README.md:39-44`.
- **Storlek:** M

### B2. Gotenbergs PDF/A-värden (2b, 3b) avvisas, och PDF/A-kedjan är hårdkodad för PDF/A-1
- **Varför:**
  - **Fel värden.** Gotenberg 8 tar emot `PDF/A-1b`, `PDF/A-2b` och `PDF/A-3b`, men inte 1a. Här finns bara 1a/1b, och allt annat blir `400 UNSUPPORTED_OPTION`. Ett test låser fast att 2b ger 400. Klienter som skickar 2b eller 3b får ett fel i stället för en fil. Att tyst ge 1b vore fel märkning.
  - **PDF/A-1-antaganden.** Normaliseringen utgår överallt från PDF/A-1:
    - XMP skriver alltid `part=1`, och filen sparas okomprimerad.
    - Transparens plattas alltid ut. Mjuka masker, JPX och 16-bitarsbilder görs om, och ICC v4 nedgraderas.
    - ExtGState-transparens rapporteras som fel. För Chromium-PDF:er ger det falska `TRANSPARENCY`-fel, onödigt stora filer och visuella ändringar.
  - **Nivåjämförelse.** Reservnivån vid saknade taggar är hårdkodad till 1b. `PdfaService` underkänner en fil vars nivå skiljer sig från den begärda, så 2b som blir 2a ger `X-Pdfa-Compliant=false`. För en zip tas `X-Pdfa-Level` från första filen.
- **Belägg:**
  - `src/main/java/se/fileconverter/pdfa/PdfaLevel.java:6-7`, `:22-33`
  - `src/main/java/se/fileconverter/api/ErrorHandler.java:35-39`
  - `src/test/java/se/fileconverter/api/ConversionControllerTest.java:521-523`
  - `src/main/java/se/fileconverter/pdfa/PdfaNormalizer.java:93`, `:117-143`, `:164-176`, `:329-357`, `:411-414`, `:463-478`, `:575-603`, `:743-747`
  - `src/main/java/se/fileconverter/pdfa/PdfaService.java:401`
  - `src/main/java/se/fileconverter/html/HtmlConverter.java:84-90`
  - `src/main/java/se/fileconverter/api/ConversionController.java:110-113`, `:130-131`, `:151-163`, `:199-201`
  - `README.md:3-4`, `:212`
- **Förslag:**
  - **`PdfaLevel`:** lägg till 2A/2B/2U/3A/3B/3U med `part()`, `flavour`, `requiresTagging()`, `untaggedFallback()`, `allowsTransparency()`, `allowsAttachments()` och `satisfies(requested)`. Felmeddelandet ska lista alla värden.
  - **`PdfaNormalizer`:** hoppa över utplattning, SMask/JPX/16-bitsomkodning, ICC-nedgradering och `CA=1` när `part≥2`, och spara med objektströmmar. Skriv `setPart(level.part())`.
  - **`PdfaService:401`:** `compliant = report.compliant() && level().satisfies(requested)`.
  - **`HtmlConverter`:** mappa alla nivåer till `PdfAConformance`.
  - **`X-Pdfa-Level` för zip:** lägsta nivån eller en lista.
  - **Testet:** byt till ett verkligt ogiltigt värde (t.ex. `PDF/A-4f`) och lägg till positiva tester för 2b/3b på alla fyra routes.
  - **Övrigt:** uppdatera admin (`AdminController:147`, `admin/index.html:87`) och README-tabellerna. `PdfaValidator` fungerar redan via `level.flavour()` (`PdfaValidator.java:432-441`).
- **Storlek:** L

### B3. Chromium-motor med automatiskt motorval saknas
- **Varför:** `/forms/chromium/convert/html` renderas alltid med openhtmltopdf. Den klarar inte flexbox, grid eller JavaScript:
  - `<script>` ignoreras utan varning.
  - Inline-SVG ritas inte, eftersom SVG-tillägget saknas i `pom.xml`.
  - `opacity` och skuggor ignoreras.

  Klienternas mallar är byggda för Gotenbergs Chromium och blir tyst felaktiga. Det finns ingen motorabstraktion: controllern anropar `HtmlConverter` direkt. `HeaderFooter` lägger header- och footerstilar i hela dokumentet, medan Chromium renderar dem isolerat.
- **Belägg:**
  - `src/main/java/se/fileconverter/html/HtmlConverter.java:23-28`, `:102-114`
  - `src/main/java/se/fileconverter/html/HeaderFooter.java:192`
  - `src/main/java/se/fileconverter/api/ConversionController.java:103-123`
  - `src/main/java/se/fileconverter/api/GotenbergOptions.java:100-112`
  - `pom.xml:84-88`
  - `README.md:205-208`
- **Förslag:** Inför `HtmlEngine` (OPENHTMLTOPDF, CHROMIUM) och `EngineSelector`.
  - **Underlag:** selectorn läser `index.html`, alla `*.css` i arbetskatalogen och `header.html`/`footer.html`, efter att CSS-kommentarer tagits bort.
  - **Signaler som väljer Chromium:**
    - `<script>` (utom JSON och ld+json), `on*=` och `javascript:`
    - `display:flex|grid`, `grid-template*`, `position:sticky`, `var(--`, `@supports`, `@container`
    - `transform`, `filter`, `box-shadow`, `opacity<1`, `column-count`
    - `<svg>`, `<canvas>`, `<iframe>`
    - fälten `waitDelay`/`waitFor*`, `emulatedMediaType=screen`, `scale≠1`, `singlePage`, `omitBackground`, `generateTaggedPdf`

    `calc()` kontrolleras mot openhtmltopdf 1.1.100 först. Externa resurser avgör inte valet.
  - **Ordning:**
    1. Fältet `converterEngine=chromium|openhtmltopdf`. Ett okänt värde ger 400.
    2. En Chromium-signal.
    3. Annars openhtmltopdf.
  - **Avvisningar:** de i `GotenbergOptions:100-112` gäller bara openhtmltopdf-vägen.
  - **Synlighet:** svarshuvudena `X-Converter-Engine` och `X-Converter-Engine-Reason` (en begränsad lista), taggen `engine` på `converter_conversions_total` och `converter_conversion_duration_seconds`, mätvärdet `converter_engine_selected_total{engine,reason}`, samt motor och orsak på loggraden.
  - **Testbänk och tester:** testbänken (`CheckService:89-97`) visar och kan tvinga motor. Enhetstester med fixtures per signal.
- **Storlek:** XL

### B4. Chromium kräver sandlåda, spärrat nätverk, egen samtidighetsgräns och egen hälsokontroll
- **Varför:** Chromium kör godtycklig JS.
  - **Filåtkomst:** utan spärr kan `file:///etc/...` läsas in i PDF:en. `egress: []` hjälper inte mot `file://`.
  - **Samtidighet och hälsa:** det finns en enda semafor för alla routes, och `/health` känner bara till LibreOffice.
  - **Resurser:** `/dev/shm` är 64 MB som standard. Minnesgränsen är 3Gi, och JVM:en tar 40 %.
  - **Sandlåda:** `runAsUser 1001` ger normalt inte de user namespaces som sandlådan kräver.
  - **Paket:** apt-paketet `chromium` på Ubuntu 26.04 är en snap och fungerar inte i en container.
- **Belägg:**
  - `src/main/java/se/fileconverter/html/HtmlConverter.java:81-83`, `:116-133`
  - `src/main/java/se/fileconverter/api/ConcurrencyLimit.java:36`, `:44-45`
  - `src/main/java/se/fileconverter/api/ConversionController.java:72-78`
  - `deploy/k8s/networkpolicy.yaml:11-13`
  - `deploy/k8s/deployment.yaml:21-23`, `:43-48`, `:60-62`
  - `Dockerfile:40`
- **Förslag:** Ny klass `ChromiumConverter`.
  - **Start:** `chrome-headless-shell` (Chrome for Testing eller Playwrights build, inte apt) via `--remote-debugging-pipe`, med `--host-resolver-rules="MAP * ~NOTFOUND"`, `--proxy-server=127.0.0.1:9` och `--disable-background-networking`.
  - **Isolering:** en ny inkognito-kontext per anrop. `Fetch.enable` släpper bara igenom `data:` och `file://` under arbetskatalogen, med samma regel som `isAllowed`.
  - **Tidsgränser:** ett tak för `waitDelay`, en tidsgräns per jobb, och omstart av webbläsaren efter N jobb.
  - **Sandlåda:** använd den om klustret tillåter user namespaces. Annars `--no-sandbox` plus seccomp `RuntimeDefault` och skrivskyddat rootfs, och beslutet dokumenteras.
  - **Konfiguration:** `converter.chromium.*` med en egen semafor (`CONVERTER_CHROMIUM_MAX_CONCURRENT`), och `details.chromium` i `/health` och startproben.
  - **Resurser:** en `emptyDir` med `medium: Memory` på `/dev/shm`. Räkna med ungefär 150–300 MB per sida och 300–400 MB större image, och mät i lasttestet.
  - **Tester:** att `file:///etc/hostname` och `169.254.169.254` inte når PDF:en, och att en JS-timeout ger fel.
- **Storlek:** L

### B5. Gotenbergs Chromium-fält ignoreras eller avvisas
- **Varför:**
  - **Ignoreras:** `waitDelay`, `waitFor*`, `cookies`, `userAgent`, `extraHttpHeaders`, `failOn*`, `skipNetworkIdleEvent`, `omitBackground` och `generateTaggedPdf`.
  - **Avvisas:** `singlePage`, `scale≠1` och `emulatedMediaType=screen`.

  Enligt beslutet ska de fungera på Chromium-vägen. Med spärrat nätverk har `cookies`, `extraHttpHeaders` och `failOnHttpStatusCodes` ingen verkan, så det måste bestämmas uttryckligen hur de hanteras.
- **Belägg:**
  - `src/main/java/se/fileconverter/api/GotenbergOptions.java:30-33`, `:57-59`, `:100-112`
  - `src/main/java/se/fileconverter/api/ConversionController.java:115-116`, `:250-279`
  - `src/test/java/se/fileconverter/api/ConversionControllerTest.java:428-443`
  - `README.md:43`
- **Förslag:** Dela `GotenbergOptions` i en gemensam del och en motorspecifik del (`ChromiumOptions`), och mappa fälten till CDP:
  - **`Page.printToPDF`:** pappers- och marginalfält, `landscape`, `scale`, `preferCssPageSize`, `printBackground`, `nativePageRanges` och `generateTaggedPdf`.
  - **Header och footer:** `header.html`/`footer.html` blir `headerTemplate`/`footerTemplate`, utan `HeaderFooter`-sammanslagningen.
  - **Emulation:** `omitBackground`, `emulatedMediaType` och `userAgent` via `Emulation.*`.
  - **Väntan:** `waitDelay` med tak, `waitForExpression` och `waitForSelector`.
  - **failOn\*:** felet ges med samma statuskod som Gotenberg (kontrollera, troligen 409).
  - **`singlePage`:** mät dokumenthöjden.
  - **`cookies` och `extraHttpHeaders`:** listas som ignorerade, med en README-rad om varför.

  Lägg till en kolumn per motor i `README.md:39-44` och tester per fält.
- **Storlek:** L

### B6. Standardformat och marginaler för HTML skiljer sig från Gotenberg
- **Varför:** Utan fält ger Gotenberg Letter, marginaler på 0.39in och `preferCssPageSize=false`. Här blir det A4 och mallens egen `@page`. Mallar som är kalibrerade mot Gotenberg kan få andra sidbrytningar.
- **Belägg:**
  - `src/main/java/se/fileconverter/api/ConversionController.java:250-267`
  - `src/main/java/se/fileconverter/html/HtmlConverter.java:79`
  - `README.md:211`
- **Förslag:** Följ Gotenbergs standardvärden exakt på Chromium-vägen. Behåll A4 på openhtmltopdf för egna mallar med `@page`. Mät med B1 hur ofta pappers- och marginalfält saknas. Lägg till ett test för sidstorlek och marginaler utan fält.
- **Storlek:** S

### B7. LibreOffice-kötimeout, tidsgräns och krasch blir `422 LIBREOFFICE_FAILED`
- **Varför:** Alla `OfficeException` blir 422 och räknas som utfallet `avvisad`. Det gäller även tre fall som beror på last eller miljö:
  - kötimeout (30 s)
  - task-timeout (120 s)
  - en soffice-process som kraschar

  Klienten får ett slutgiltigt fel som ser ut att bero på filen, och försöker inte igen. Larm på `outcome="fel"` eller 5xx missar överlast. Gotenberg svarar 503 i sådana fall.
- **Belägg:**
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:125-128`, `:234-236`
  - `src/main/java/se/fileconverter/observability/ConversionRecord.java:46-47`
  - `src/main/java/se/fileconverter/api/ConcurrencyLimit.java:59-63` (enda stället med 503 och `Retry-After`)
  - `src/main/resources/application.yml:37-39`
- **Förslag:** Skilj på feltyperna i `OfficeConverter.export`:

  | Fall | Svar | Orsakskod |
  |---|---|---|
  | Kötimeout | 503 med `Retry-After` | `BUSY` |
  | Tidsgräns | 503, som Gotenberg | `TIMEOUT` eller `LIBREOFFICE_TIMEOUT` |
  | Processkrasch | 503 eller 500, gärna ett nytt försök mot en ny process | ny orsakskod |
  | Oläsbart dokument | 422 | `LIBREOFFICE_FAILED` (som idag) |

  Lägg orsakskoderna i `ConversionException.Reason` och README. Testa med pool-size 1, två samtidiga anrop och kort kötimeout, och med en soffice som dödas mitt i jobbet.
- **Storlek:** M

### B8. Samtidighetsgränsen följer nodens CPU-antal, och minnet är inte dimensionerat
- **Varför:** `max-concurrent: 0` betyder `availableProcessors()`. Det finns ingen CPU-limit och ingen `CONVERTER_LIMITS_MAX_CONCURRENT`, så en podd på en nod med 32 kärnor släpper in 32 anrop.
  - **Kö:** bara 2 av dem kan använda LibreOffice-poolen, och resten köar i JODConverter, vilket ger B7.
  - **Minne:** varje anrop håller upp till 100 MB och flera `byte[]`-kopior i en heap på cirka 1,2 GiB. Till det kommer 2 soffice-processer utanför heapen.
  - **QoS:** request och limit för minne skiljer sig (Burstable), så podden riskerar OOM eller eviction.
- **Belägg:**
  - `src/main/resources/application.yml:5-7`, `:65-67`
  - `src/main/java/se/fileconverter/api/ConcurrencyLimit.java:44`
  - `deploy/k8s/deployment.yaml:33-35`, `:43-48`
  - `Dockerfile:40`
- **Förslag:**
  - Sätt `CONVERTER_LIMITS_MAX_CONCURRENT` uttryckligen (t.ex. pool-size + 2), gärna med en egen gräns för Office-routen som är lika med `poolSize`.
  - Lägg `-XX:ActiveProcessorCount` i `JAVA_TOOL_OPTIONS`.
  - Sätt memory request lika med limit.
  - Lägg till en startkontroll som varnar när heap + pool × LibreOffice-minne överskrider containerns limit.

  Formeln dokumenteras i kapacitetsguiden (F17).
- **Storlek:** M

### B9. Imagen publiceras inte i Harbor
- **Varför:** CI bygger `file-converter:ci` och kör ett röktest, men pushar aldrig. Det saknas:
  - versionstaggar, SBOM, skanning och releaseflöde
  - en oföränderlig version att peka på, eftersom manifestet pekar på `:latest` och pom har `0.1.0-SNAPSHOT`

  Utan det kan GitOps inte låsa en version, och rollback går inte.
- **Belägg:**
  - `.github/workflows/ci.yml:3-5`, `:26-38`
  - `deploy/k8s/deployment.yaml:26`
  - `pom.xml:16`
- **Förslag:** Lägg till jobbet `publish`, som körs på main och på taggar `v*`:
  - inloggning mot Harbor med secrets
  - `docker/build-push-action` och `metadata-action` med taggarna `sha-<kort>`, `<semver>` och `<major>.<minor>`, aldrig `latest` i prod
  - SBOM och provenance
  - Trivy som stoppar vid CRITICAL eller HIGH, eller Harbors egen policy
  - gärna cosign
  - release-please eller motsvarande för version och changelog
  - utskrift av image-digesten för GitOps
- **Storlek:** M

### B10. Ingen kustomize-bas med overlays, ingen hantering av hemligheter och ingen pull-secret
- **Varför:**
  - **Struktur:** det finns en enda kustomization, utan namespace och utan `images:`.
  - **Admin-lösenordet:** skapas för hand och är `optional: true`, så en saknad hemlighet stänger av `/admin` tyst.
  - **Harbor:** pull-secret för ett privat projekt saknas.
  - **Volymen:** PVC:n kan beskäras (prune) av Argo eller Flux.
- **Belägg:**
  - `deploy/k8s/kustomization.yaml:1-8`
  - `deploy/k8s/admin-secret.example.yaml:1-9`
  - `deploy/k8s/deployment.yaml:26`, `:36-42`
- **Förslag:**
  - Flytta filerna till `deploy/k8s/base/` och skapa `overlays/test` och `overlays/prod`. Varje overlay får namespace, `images:` med tagg eller digest, resurser, HPA, `storageClassName` och env.
  - Hemligheten blir en ExternalSecret eller SealedSecret per overlay.
  - Lägg till `imagePullSecrets`.
  - Märk PVC:n med `Prune=false` eller motsvarande.
  - Uppdatera README avsnittet Köra.
- **Storlek:** M

### B11. NetworkPolicy saknar ingress-regler
- **Varför:** Ägarens beslut att avstå från autentisering vilar på NetworkPolicy och ingress-regler. Policyn styr bara egress, så port 3000 (API och `/admin`) och 9090 kan nås från hela klustret. `egress: []` stänger avsiktligt av DNS, och det bör dokumenteras.
- **Belägg:**
  - `deploy/k8s/networkpolicy.yaml:11-13`
  - `deploy/k8s/service.yaml:8-10`
  - `README.md:216-217`
- **Förslag:** Lägg till `Ingress` med två regler:
  - Port 3000 bara från klienternas namespaces och poddar, med selektorer per overlay.
  - Port 9090 bara från monitoring-namespacet.

  Lägg `/admin` bakom en egen ingress med IP-begränsning, eller dokumentera att port-forward används.
- **Storlek:** S

### B12. Delad volym: ReadWriteMany krävs men behörigheterna saknas
- **Varför:**
  - **Storage class:** utan en RWX-klass blir PVC:n Pending.
  - **Behörigheter:** utan `fsGroup` kan uid 1001 inte skapa `/data/fonts`. `AssetStore` kastar då ett fel, och appen startar inte (CrashLoopBackOff).
  - **NFS:** fingeravtrycket bygger på mtime, som kan fördröjas av attributcachning.
- **Belägg:**
  - `deploy/k8s/pvc.yaml:1-13`
  - `deploy/k8s/deployment.yaml:21-23`
  - `src/main/java/se/fileconverter/assets/AssetStore.java:53-60`, `:128-142`
- **Förslag:**
  - Sätt `storageClassName` per overlay.
  - Lägg till `fsGroup: 1001` och `fsGroupChangePolicy: OnRootMismatch`.
  - Låt `AssetStore` starta i läsläge med en varning i stället för att krascha.
  - Om ingen RWX-klass finns: lägg resurserna i en ConfigMap eller ett OCI-artefakt via GitOps, och stäng av skrivning i `/admin` i prod.
- **Storlek:** M

### B13. Prometheus-skrapning via annoteringar, och Service saknar metrics-port
- **Varför:** Prometheus Operator läser inte `prometheus.io/*` som standard, och Service exponerar bara 3000. Utan skrapning blir alla larm och dashboards tomma.
- **Belägg:**
  - `deploy/k8s/deployment.yaml:16-19`, `:30-31`
  - `deploy/k8s/service.yaml:8-10`
  - `deploy/k8s/kustomization.yaml:3-8`
  - `README.md:183-184`
- **Förslag:** Lägg till en PodMonitor (port `metrics`, `/actuator/prometheus`, 30 s), eller en namngiven port 9090 plus ServiceMonitor. Släpp in monitoring på 9090 i B11.
- **Storlek:** S

### B14. Larmregler (PrometheusRule) saknas
- **Varför:** Vid ett direkt byte är larmen det som avgör när rollback ska göras. Idag finns inga.
- **Belägg:** `deploy/k8s/kustomization.yaml:3-8`, `README.md:181-201`
- **Förslag:** Lägg till `deploy/k8s/base/prometheusrule.yaml` med:
  - felkvot (`outcome="fel"`, utan `route="testbank"`) över 2 % i 10 min
  - andel underkända PDF/A
  - `converter_busy_total` och 503
  - p95-latens per route
  - `LIBREOFFICE_*`-avvisningar, `converter_libreoffice_up == 0` och omstarter (när F16 finns)
  - poddar som inte är ready, och omstarter
  - HPA som står på max

  Trösklarna sätts efter lasttestet, och varje larm får en `runbook_url`.
- **Storlek:** M

### B15. Runbook saknas
- **Varför:** README beskriver funktionerna, men inte hur driften ska agera. Det saknas:
  - vad varje orsakskod betyder och vad man gör
  - hur ett `Gotenberg-Trace` följs i Loki/Elastic
  - omstart, och rollback till Gotenberg
  - hur fonter och alias förs över med export och import
- **Belägg:**
  - `README.md:60-80`, `:151-179`, `:181-201`
  - `src/main/java/se/fileconverter/api/ConversionException.java:14-30`
  - `src/main/java/se/fileconverter/assets/AssetReloader.java:53-55`
  - `src/main/java/se/fileconverter/admin/AdminController.java:125-142`
- **Förslag:** Skapa `docs/runbook.md` med sex avsnitt:
  1. En tabell: orsakskod, HTTP-status, trolig orsak, åtgärd och larm.
  2. Felsökning med traceId.
  3. `kubectl rollout restart` och vad som händer med pågående jobb.
  4. Rollback, med kriterier från B14 och en hänvisning till F18.
  5. Fontimport: backup av PVC, export från test, import i prod, och kontroll av att alla poddar laddat om.
  6. Uppgradering av LibreOffice.
- **Storlek:** M

## Före bytet

### F1. LibreOffice 26.2: versionsberoende kod, reproducerbara byggen och versionsspårning
- **Varför:** Uppgraderingen görs i samma PR (`fec62b6`). Alla 76 tester går igenom, och `.xlsx` blir nu taggad 1a. Flera ställen beror ändå på hur LibreOffice beter sig:
  - **Omexporten.** Omexporten med `SelectPdfVersion=1` vid transparens förutsätter att PDF/A-1-läget plattar ut transparens.
  - **Profil och filter.** Nycklarna i registerprofilen ignoreras tyst om de byts. Där ligger säkerhetsskyddet mot länkade resurser. `LandscapeFilter` använder UNO-egenskaper, och JODConverter är version 5.1.1.
  - **Bygget.** Det är inte reproducerbart: `apt-get upgrade`, inga låsta versioner och basimagen pekas ut med tagg i stället för digest.
  - **Testerna.** Integrationstesterna hoppas över tyst (`@EnabledIf`) om soffice saknas.
  - **Java-versioner.** CI testar på Java 21, bygget använder JDK 26 och runtime är JRE 25.
  - **Spårning.** Versionen syns inte i `/health` eller i mätvärden.
- **Belägg:**
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:59-67`, `:199-208`, `:219`
  - `src/main/java/se/fileconverter/office/LandscapeFilter.java:20-35`
  - `src/main/java/se/fileconverter/pdfa/PdfaNormalizer.java:262-296`
  - `src/test/java/se/fileconverter/api/ConversionControllerTest.java:45`, `:62`, `:80-129`
  - `src/test/java/se/fileconverter/admin/AdminControllerTest.java:46`
  - `Dockerfile:1`, `:9`, `:14-26`
  - `.github/workflows/ci.yml:19-22`
  - `pom.xml:21`, `:25`
  - `README.md:243`
- **Förslag:**
  - **Spårning:** logga `soffice --version` vid start och exponera den i `/health` och i `converter_build_info{libreoffice,java}`.
  - **CI:** sätt `REQUIRE_LIBREOFFICE=true` så att `@EnabledIf`-testerna fallerar i stället för att hoppas över. Kör integrationstesterna även inne i den byggda imagen. Sätt `java-version: "25"`.
  - **Image:** pinna basimagen på digest och låt Dependabot uppdatera den.
  - **Test av registerprofilen:** ett test med en länkad bild.
  - **Robusta tester:** testet av `X-Pdfa-Problems` ska kontrollera att värdet innehåller `UNTAGGED`, inte att det är exakt lika.
- **Storlek:** M

### F2. LibreOffice kan exportera PDF/A-2/3 direkt
- **Varför:** `OfficeConverter` exporterar redan taggad PDF/A-2, men bara som mellansteg till PDF/A-1. Vid transparens exporteras filen om, och då försvinner taggarna. Om PDF/A-2/3 begärs behövs inget av det. `addOriginalDocumentAsStream` avvisas, fast PDF/A-3 tillåter bifogade filer.
- **Belägg:**
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:33-36`, `:195-210`
  - `src/main/java/se/fileconverter/office/OfficeOptions.java:16`
  - `src/main/java/se/fileconverter/api/GotenbergOptions.java:113-116`, `:165-191`
  - `src/main/java/se/fileconverter/admin/CheckService.java:107-109`
- **Förslag:** Byt `boolean pdfA` mot `Optional<PdfaLevel>` plus `pdfua`.
  - **Part 2/3:** `SelectPdfVersion=part` och `UseTaggedPDF`, utan omexport. Med PDF/A-3 sätts `IsAddStream`.
  - **Efterbehandling:** `PdfaService` rättar bara metadata och validerar.
  - **Tester:** `presentation-transparens.pptx` som 2a med taggarna kvar, och `beslut.docx` som 3b med originalet bifogat.
- **Storlek:** M

### F3. Fontalias och uppladdade fonter når inte Chromium
- **Varför:**
  - **Motorer:** alias registreras bara i openhtmltopdf, LibreOffice får fonter men inga alias, och Chromium ser bara systemets fontconfig. Eftersom motorvalet är automatiskt får klienten olika fonter utan att märka det.
  - **Omstart:** vid fontändringar startas bara LibreOffice om.
- **Belägg:**
  - `src/main/java/se/fileconverter/html/FontRegistry.java:89-105`
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:103-121`, `:143-163`
  - `docker/fonts.conf`
- **Förslag:** Lägg till en gemensam `FontconfigWriter` som skriver `<dir>` och `<alias><prefer>`. Både LibreOffice och Chromium startar med `FONTCONFIG_FILE`. `AssetReloader` startar även om Chromium.
- **Storlek:** M

### F4. Fontalias vars målfont saknas ignoreras tyst
- **Varför:**
  - **Tysta alias:** bara `.ttf` läses, och ett alias utan träff hoppas över utan logg. Byter fontpaketen i Ubuntu 26.04 namn, sökväg eller format, slutar `Arial → Liberation Sans` att fungera. Då blir HTML-mallarna underkända som PDF/A eller får andra metriska mått.
  - **Två Liberation:** både Liberation 1 och 2 installeras med samma familjenamn, så valet mellan dem är ospecificerat.
- **Belägg:**
  - `src/main/java/se/fileconverter/html/FontRegistry.java:72`, `:94-104`, `:117`
  - `src/main/resources/application.yml:41-57`
  - `Dockerfile:22-23`
- **Förslag:**
  - Logga en WARN per alias utan mål och exponera det i `/health` eller i ett mätvärde.
  - Lägg till `FontRegistryTest`, som körs i CI på `ubuntu-26.04` och kräver att alla standardalias hittar en font.
  - Ta bort `fonts-liberation` (v1) eller sortera valet så att det blir deterministiskt.
- **Storlek:** S

### F5. Nyare Gotenberg-fält och webhookhuvuden hanteras inte uttryckligen
- **Varför:**
  - **Fält utan verkan:** `userPassword`/`ownerPassword` ger tyst en okrypterad fil. `watermark`/`stamp`, `embeds` och LibreOffice-fält som saknas i listan har ingen verkan.
  - **Webhook:** bara `Gotenberg-Webhook-Url` avvisas, fast README säger `Gotenberg-Webhook-*`.
- **Belägg:**
  - `src/main/java/se/fileconverter/api/GotenbergOptions.java:36-52`, `:54-60`, `:82-84`, `:88-99`
  - `README.md:41`
- **Förslag:**
  - Lägg till mängden `REJECT_IF_PRESENT` (lösenord, watermark, stamp) med ett tydligt 400-meddelande.
  - Avvisa alla `Gotenberg-Webhook-*`.
  - Stäm av fältlistan mot den Gotenberg-version som körs i produktion.
- **Storlek:** S

### F6. Format på svar och hälsokontroll skiljer sig från Gotenberg
- **Varför:**
  - Fel returneras som `problem+json`, medan Gotenberg svarar med text.
  - Statuskoderna skiljer sig, se B7.
  - `/health` har `details.libreoffice` som sträng, inte som objekt.
  - `/version` saknas.
  - Standardnamnen på utdatafiler kan skilja sig.
- **Belägg:**
  - `src/main/java/se/fileconverter/api/ErrorHandler.java:91-137`
  - `src/main/java/se/fileconverter/api/ConversionController.java:72-78`, `:208-217`
  - `src/test/java/se/fileconverter/api/ConversionControllerTest.java:527-530`
- **Förslag:** Gå igenom hur klientkoden tolkar svaren.
  - Ge `/health` samma form som Gotenberg (`{status, details:{libreoffice:{status,timestamp}, chromium:{…}}}`).
  - Lägg till `GET /version`.
  - Dokumentera `problem+json` under Skillnader mot Gotenberg.
  - Lägg till kontraktstester som låser fast formen.
- **Storlek:** S

### F7. Tidsgränser saknas för HTML, efterbehandling, veraPDF och hela anropet
- **Varför:** Bara LibreOffice har tidsgränser. openhtmltopdf, PDFBox, normaliseringen och veraPDF körs synkront utan gräns. En patologisk mall eller PDF kan hålla en plats i semaforen och CPU utan slut. Flerfilsanrop får 120 s per fil, och det finns ingen gräns för antalet filer. Det blir viktigare när motorvalet styr fler mallar till openhtmltopdf.
- **Belägg:**
  - `src/main/java/se/fileconverter/html/HtmlConverter.java:93-95`
  - `src/main/java/se/fileconverter/api/ConversionController.java:91-93`, `:102-140`, `:170-180`
  - `src/main/java/se/fileconverter/pdf/PdfMerger.java:18-27`
  - `src/main/java/se/fileconverter/api/ConcurrencyLimit.java:65-74`
- **Förslag:**
  - Lägg till `converter.limits.request-timeout` (t.ex. 180 s) och `converter.limits.max-files`.
  - Kör kärnan i en virtuell tråd med `Future.get(timeout)`. Vid timeout: `cancel(true)` och 503 med orsaken `TIMEOUT`.
  - Kontrollera `Thread.interrupted()` i loopar i normaliseringen.
  - Lägg till ett test med en tung mall.
- **Storlek:** M

### F8. Ingen graceful shutdown för långa konverteringar
- **Varför:** Grace-perioden och timeouten för avstängning är 30 s som standard, och `preStop` saknas. En Office-konvertering kan ta 60 s i kö, 30 s till och 120 s, och dubbelt så länge vid transparens. Varje rollout, nedskalning och nodtömning bryter pågående anrop. `OfficeConverter.destroy` väntar inte ut läslåset.
- **Belägg:**
  - `deploy/k8s/deployment.yaml:20-64`
  - `src/main/resources/application.yml:12-13`, `:38-39`, `:66-67`
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:86-91`, `:200-208`
- **Förslag:**
  - `preStop` med sleep på 10 s och `terminationGracePeriodSeconds: 200`.
  - `server.shutdown: graceful` och `spring.lifecycle.timeout-per-shutdown-phase: 180s`.
  - `destroy` tar skrivlåset med en timeout.
  - Rollout-strategin `maxUnavailable: 0`, `maxSurge: 1`.
- **Storlek:** S

### F9. PodDisruptionBudget och spridning över noder och zoner saknas
- **Varför:** Med 2 repliker utan PDB kan en nodtömning ta ner båda poddarna, och de kan hamna på samma nod. Utan Gotenberg som reserv blir det ett totalt avbrott.
- **Belägg:**
  - `deploy/k8s/kustomization.yaml:3-8`
  - `deploy/k8s/deployment.yaml:20-24`
  - `deploy/k8s/hpa.yaml:10`
- **Förslag:** Lägg till `pdb.yaml` med `maxUnavailable: 1` och `topologySpreadConstraints` på värd och zon. Sätt gärna `minReplicas: 3` i prod.
- **Storlek:** S

### F10. Liveness och readiness beror på LibreOffice, och en fontändring startar om LibreOffice i alla poddar samtidigt
- **Varför:**
  - **Prober:** startup, readiness och liveness går alla mot `/health`, som beror på LibreOffice. En misslyckad omstart lämnar poolen stoppad, och podden startas om efter cirka 2 min i stället för att bara tas ur trafik.
  - **Fontändringar:** `AssetReloader` pollar var 30:e sekund utan jitter och anropar `restart()` i alla poddar inom samma fönster. Skrivlåset väntar ut exporter på upp till 120 s, och då står Office-anropen stilla i hela klustret. Under stop och start är alla poddar oready samtidigt. README säger "några sekunder", vilket underskattar det.
  - **Mätvärden:** inga visar omstarter.
- **Belägg:**
  - `deploy/k8s/deployment.yaml:49-59`
  - `src/main/java/se/fileconverter/api/ConversionController.java:71-78`
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:49`, `:103-121`, `:214`
  - `src/main/java/se/fileconverter/assets/AssetReloader.java:37-38`, `:42-57`
  - `src/main/resources/application.yml:16-22`, `:63-64`
  - `README.md:169-171`
- **Förslag:**
  - Sätt `management.endpoint.health.probes.enabled=true`. Liveness går mot `:9090/actuator/health/liveness` (bara JVM:en). Readiness får en HealthIndicator för LibreOffice. `/health` på 3000 behålls för kompatibilitet.
  - Lägg jitter i `AssetReloader`, eller låt en podd i taget starta om via en låsfil på PVC:n.
  - Starta helst en ny `OfficeManager` innan den gamla stoppas.
  - Lägg till mätvärden enligt F16.
- **Storlek:** M

### F11. Uppladdningar skrivs till `/tmp` innan samtidighetsgränsen
- **Varför:** Multipart-kroppen läses till disk innan interceptorn `ConcurrencyLimit` körs.
  - **Köande anrop:** de har redan hela uppladdningen i `/tmp`, upp till 100 MB, och antalet är obegränsat.
  - **Kopior:** `Workspace` kopierar filerna en gång till, och LibreOffice skriver bredvid.
  - **Vräkning:** överskrids `sizeLimit 4Gi` vräks hela podden. Det syns varken som 503 eller i `converter_*`.
  - **Schemaläggning:** `ephemeral-storage` saknas.
  - **Städning:** `Workspace.close` ignorerar fel tyst.
- **Belägg:**
  - `src/main/java/se/fileconverter/api/ConcurrencyLimit.java:26`, `:55-66`
  - `src/main/java/se/fileconverter/api/Workspace.java:24`, `:39`, `:49-56`
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:213`
  - `deploy/k8s/deployment.yaml:43-48`, `:61-68`
  - `src/main/resources/application.yml:5-7`
- **Förslag:**
  - Gör gränsen till ett servlet-filter för `POST /forms/**` före multipart-tolkningen, med en maximal kölängd som ger 503 direkt.
  - Sätt `ephemeral-storage` för requests och limits, och dimensionera `sizeLimit` efter max-concurrent × 3 × filstorlek.
  - Logga och räkna misslyckad städning, och städa gamla `convert-*` och `lu*.tmp` regelbundet.
- **Storlek:** M

### F12. JVM:en avslutas inte vid OutOfMemoryError
- **Varför:** Bara `MaxRAMPercentage=40` är satt.
  - **ErrorHandler:** fångar bara `RuntimeException`, så ett OOM registreras som utfallet `ok`.
  - **JVM:en:** lever vidare i ett osäkert tillstånd, och `/health` svarar 200.
  - **Minne:** `PdfMerger` använder bara minnescache.
- **Belägg:**
  - `Dockerfile:40`
  - `src/main/java/se/fileconverter/api/ErrorHandler.java:52`
  - `src/main/java/se/fileconverter/observability/ConversionTracingFilter.java:55`, `:59`
  - `src/main/java/se/fileconverter/pdf/PdfMerger.java:23-27`
- **Förslag:**
  - Lägg till `-XX:+ExitOnOutOfMemoryError` och gärna `-XX:+HeapDumpOnOutOfMemoryError`.
  - Fånga `Throwable` i filtret, markera utfallet som `fel` och kasta vidare.
  - Låt `PdfMerger` använda `MemoryUsageSetting.setupMixed`.
  - Larma på `OOMKilled`.
- **Storlek:** S

### F13. Ingen init-process i imagen
- **Varför:** `java` är PID 1. Föräldralösa `soffice.bin` blir zombie-processer. JODConverters `UnixProcessManager` behöver `ps`, men `procps` installeras inte. Processkedjan bör verifieras med LibreOffice 26.2.
- **Belägg:**
  - `Dockerfile:14-26`, `:42`
  - `src/main/resources/application.yml:38-40`
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:124-133`
- **Förslag:** Installera `tini` och `procps`, och sätt `ENTRYPOINT ["tini","--","java","-jar","/app/app.jar"]`. Kontrollera i röktestet med `ps -e` att det inte finns `<defunct>`-processer.
- **Storlek:** S

### F14. Strukturerade loggar är inte påslagna, och loggarna innehåller kundernas filnamn
- **Varför:**
  - **Format:** `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` sätts inte i manifesten. Loggraden är en formaterad sträng, så fälten blir inte egna ECS-fält.
  - **Personuppgifter:** originalfilnamn loggas på info-nivå och visas i `/admin`. De kan innehålla personuppgifter, och det är ett nytt informationsflöde jämfört med Gotenberg.
- **Belägg:**
  - `deploy/k8s/deployment.yaml:32-42`
  - `src/main/resources/application.yml:27-31`
  - `src/main/java/se/fileconverter/observability/ConversionTracingFilter.java:60-62`, `:70-72`
  - `src/main/java/se/fileconverter/observability/ConversionTelemetry.java:90-97`
  - `README.md:193-199`
- **Förslag:**
  - Sätt ECS i manifesten och logga med `log.atInfo().addKeyValue(...)`.
  - Lägg till `converter.logging.file-names=none|extension|full` med standardvärdet `extension`.
  - Dokumentera valet för dataskyddsansvarig.
- **Storlek:** S

### F15. Anrop som avvisas före controllern syns inte i mätvärden och loggar
- **Varför:** 503 från `ConcurrencyLimit`, 413 och ogiltig multipart uppstår innan `track()` körs, så `record.route` är null. De räknas inte i `converter_conversions_total` och loggas inte, och `converter_busy_total` saknar route. Felkvoten underskattar alltså just överlast.
- **Belägg:**
  - `src/main/java/se/fileconverter/observability/ConversionTracingFilter.java:54`
  - `src/main/java/se/fileconverter/api/ConcurrencyLimit.java:59-62`
  - `src/main/java/se/fileconverter/api/ConversionController.java:83`
  - `src/main/java/se/fileconverter/api/ErrorHandler.java:41-49`
- **Förslag:**
  - Sätt route i filtret utifrån URI:n innan `chain.doFilter`.
  - Låt `ConcurrencyLimit` och `ErrorHandler` anropa `rejected(...)`.
  - Lägg till taggen `route` på `converter_busy_total`.
  - Lägg till ett test.
- **Storlek:** S

### F16. Saknade mätvärden för kö, pågående anrop, LibreOffice och omladdning, och latenshistogrammet slutar vid 30 s
- **Varför:**
  - **Kö och omstarter:** det finns inga mätvärden för kölängd, väntetid, pågående anrop, poolstatus, LibreOffice-omstarter eller misslyckade omladdningar. Utan dem går det inte att tuna, skala rätt eller larma.
  - **Histogrammet:** utan `maximumExpectedValue` slutar hinkarna vid 30 s, medan anrop kan ta 180 s. Då blir p95/p99 missvisande.
- **Belägg:**
  - `src/main/java/se/fileconverter/api/ConcurrencyLimit.java:44-46`, `:59`
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:97-121`
  - `src/main/java/se/fileconverter/assets/AssetReloader.java:48-55`
  - `src/main/java/se/fileconverter/observability/ConversionTelemetry.java:23-31`, `:58-64`, `:70-73`
- **Förslag:**
  - **Kö och last:** `converter_inflight`, `converter_concurrency_limit`, `converter_queue_waiting` och `converter_queue_wait_seconds{route}`.
  - **LibreOffice:** `converter_libreoffice_up` och `converter_libreoffice_restarts_total{cause}`.
  - **Omladdning:** `converter_assets_reload_total{result}` och tidsstämpeln för senaste lyckade omladdning.
  - **Histogrammet:** `maximumExpectedValue` på 4 min och SLO-hinkar på 1, 5, 15, 30, 60 och 120 s.
  - **README:** dokumentera i `README.md:186-192`.
- **Storlek:** M

### F17. HPA, lasttest och kapacitetsguide
- **Varför:** HPA skalar bara på CPU och saknar `behavior`. CPU speglar inte poolkön. Det finns inget lasttest i repot, så pool-size, max-concurrent, minne och maxReplicas är gissningar inför 1 000–50 000 per dag med toppar.
- **Belägg:**
  - `deploy/k8s/hpa.yaml:10-18`
  - `deploy/k8s/deployment.yaml:33-35`, `:43-48`
  - `src/main/resources/application.yml:37-39`, `:66-67`
  - `README.md:258`, `:265`
- **Förslag:**
  - Skapa `loadtest/` (k6) med en blandning av docx, xlsx, pptx, HTML och PDF, med och utan PDF/A, i scenarier för medel och topp (3–10 anrop/s i 15 min).
  - Lägg `behavior` i HPA, med snabb uppskalning och 300–600 s stabilisering vid nedskalning.
  - Överväg KEDA eller Prometheus Adapter på inflight eller kö.
  - Skriv `docs/kapacitet.md` med formler för minne, max-concurrent, `/tmp` och repliker, samt en startkonfiguration.
- **Storlek:** L

### F18. Plan saknas för cutover och rollback
- **Varför:** Klienterna anropar sannolikt Gotenbergs Service-namn, men det finns ingen beskrivning av hur trafiken flyttas utan kodändring, vilka kriterierna för rollback är eller hur länge Gotenberg hålls redo.
- **Belägg:**
  - `deploy/k8s/service.yaml:1-10`
  - `src/main/resources/application.yml:13`
  - README saknar ett avsnitt om övergång
- **Förslag:**
  - Lägg till `overlays/prod/cutover/`: en Service med Gotenbergs namn, port 3000 och selektorn `app: file-converter`. Byte och rollback blir då en ändring i Git.
  - Gotenberg ligger kvar med minst 1 replik i 2–4 veckor.
  - Skriv kriterier i `docs/cutover.md` (felkvot, underkända PDF/A, p95-latens, okända fält från B1) och koppla dem till larmen i B14.
- **Storlek:** M

### F19. Pod-säkerheten klarar inte PSS restricted
- **Varför:** `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, seccomp och skrivskyddat rootfs saknas. Om prod-namespacet tillämpar restricted avvisas poddarna. Härdningen är också värdefull eftersom tjänsten kör LibreOffice på kundfiler.
- **Belägg:**
  - `deploy/k8s/deployment.yaml:21-25`
  - `Dockerfile:32-34`
  - `src/main/java/se/fileconverter/office/OfficeConverter.java:143-174`
- **Förslag:** Lägg till fälten ovan och `fsGroup`, och en `emptyDir` på `/home/converter` (eller `HOME=/tmp`). Verifiera i röktestet med `--read-only --tmpfs /tmp`.
- **Storlek:** S

### F20. Grafana-dashboard saknas
- **Varför:** Statistiken i admin gäller en podd i taget och nollställs vid omstart. Den räcker inte för drift.
- **Belägg:**
  - `README.md:197-201`
  - `src/main/java/se/fileconverter/observability/ConversionTelemetry.java:105-129`
- **Förslag:** Lägg till `deploy/grafana/file-converter.json`, gärna som ConfigMap med etiketten `grafana_dashboard`. Paneler:
  - genomflöde, felkvot och orsakskoder
  - PDF/A-regler och latens per route
  - kö och 503
  - LibreOffice och motorval
  - fältanvändning (B1)
  - JVM och container-minne, och HPA

  `route=testbank` filtreras bort som standard.
- **Storlek:** M

## Efter bytet

### E1. PDF/A-3 med bifogade filer (e-faktura, originaldokument)
- **Varför:** Normaliseringen tar alltid bort `EmbeddedFiles` och `FileAttachment`. `/AF` och `AFRelationship` saknas, och XMP byggs från grunden utan tilläggsscheman (Factur-X/ZUGFeRD).
- **Belägg:**
  - `src/main/java/se/fileconverter/pdfa/PdfaNormalizer.java:96`, `:312-317`, `:377-423`
  - `src/main/java/se/fileconverter/api/GotenbergOptions.java:113-116`
- **Förslag:**
  - **Part 3:** behåll bilagorna och lägg till `/AF`, `AFRelationship` och `Subtype`.
  - **Part 2:** behåll bara bilagor som själva är PDF/A, annars tas de bort med en varning.
  - **XMP:** bevara `pdfaExtension`.
  - **`embeds`:** stöds om inventeringen visar att det används.
  - **Orsakskod:** `ATTACHMENT_NOT_ALLOWED`.
  - **Test:** en XML-bilaga validerad med `PDFA_3_B`.
- **Storlek:** M

### E2. PDF/UA (`pdfua=true`) avvisas
- **Varför:** `pdfua=true` ger alltid 400. Alla motorer kan ge taggad PDF, och veraPDF har en PDF/UA-1-profil, men validatorn tar bara en profil per nivå. XMP saknar `pdfuaid:part`. Blir blockerande om B1 visar att klienterna skickar `pdfua=true`.
- **Belägg:**
  - `src/main/java/se/fileconverter/api/GotenbergOptions.java:85-87`
  - `src/main/java/se/fileconverter/html/HtmlConverter.java:87-89`
  - `src/main/java/se/fileconverter/pdfa/PdfaValidator.java:432-441`
  - `src/main/java/se/fileconverter/pdfa/PdfaNormalizer.java:377-423`
  - `src/main/java/se/fileconverter/observability/ConversionTelemetry.java:235`
- **Förslag:**
  - **Modell:** `record PdfStandard(Optional<PdfaLevel> pdfa, boolean pdfua)`.
  - **Validering:** både PDF/A och `PDFUA_1`.
  - **Normalisering:** `pdfuaid:part=1`, `DisplayDocTitle` och `MarkInfo`, med varning om `/Lang` eller `dc:title` saknas.
  - **Synlighet:** svarshuvudena `X-Pdfua-Compliant` och `X-Pdfua-Problems`, orsakskoden `PDFUA_INVALID` och taggen `profile` på `converter_pdfa_violations_total`.
- **Storlek:** L

## Öppna frågor

| Fråga | Hur den tas reda på |
|---|---|
| Vilka routes, fält och huvuden skickar klienterna, och vilka `pdfa`-värden och `pdfua` används? | Sök i klientkoden efter `/forms/` och fältnamn, läs ingress- eller Gotenberg-loggar, och bygg mätvärdena i B1 före bytet |
| Vilken Gotenberg-version körs i produktion? | Gotenbergs `/version` eller imagetaggen i nuvarande manifest. Stäm av fältlistan (F5) |
| Hur tolkar klienterna statuskoder, `Content-Type`, `/health` och filnamn, och vilken HTTP-timeout har de? | Gå igenom klientkoden (F6, F7) |
| Vilket Service-namn och vilken port anropar klienterna? | Klientkonfiguration och nuvarande Gotenberg-Service (F18) |
| Ska Chromium-mallar som begär `PDF/A-1a/1b` bli 2a/2b, eller avvisas med t.ex. `ENGINE_PDFA_MISMATCH` i strikt läge? | Ägaren beslutar (B3) |
| Ska Gotenbergs standardformat (Letter, 0.39in) gälla exakt på Chromium-vägen? | Ägaren beslutar, med stöd av B1 (B6) |
| Tillåter klustret user namespaces för Chromiums sandlåda, och vilken PSS-nivå har prod-namespacet? | Fråga plattformsteamet (B4, F19) |
| Finns en RWX-storage class? | `kubectl get storageclass` i test och prod (B12) |
| Använder plattformen Prometheus Operator, och vilket verktyg för hemligheter (Sealed eller External Secrets)? | Fråga plattformsteamet (B10, B13) |
| Behövs PDF/A-3 med bilagor (`embeds`, `addOriginalDocumentAsStream`)? | Mätvärdet i B1 och klientkoden (E1) |
| Får filnamn loggas, och med vilken lagringstid? | Stäm av med dataskyddsansvarig (F14) |
| Vilken Java-klient ska Chromium använda (Playwright eller en ren CDP-klient)? | En spik under arbetet med B4 |

## Avgränsningar

- Ingen API-autentisering, bara interna klienter. Skyddet ligger i NetworkPolicy och ingress-regler (B11).
- Inga webhooks, inget asynkront API, ingen URL-konvertering, inga skärmdumpar och ingen split. De fortsätter att avvisas.
- Riktiga kundfiler testas separat av ägaren och förutsätts inte finnas i repot.
- Uppgraderingen till Ubuntu 26.04 och LibreOffice 26.2 görs i samma PR (`fec62b6`) och föreslås inte här. Bara följderna tas upp (F1, F4, F13).
- Testtäckning i övrigt är inget eget fokusområde.

## Borttagna fynd

Verifieringen avfärdade inga fynd. Däremot har dubbletter mellan områdena slagits ihop:

- **B1:** inventering av fält, mätvärdet för fältanvändning och `pdfa`-kardinalitet.
- **B2:** avvisade `pdfa`-värden och PDF/A-1-låsningen.
- **B7:** LibreOffice-fel som blir 422 (tre fynd).
- **B8:** samtidighet och minne, med kapacitetsguiden i F17.
- **F1:** LibreOffice-versionen (tre fynd).
- **F7:** tidsgränser för HTML, efterbehandling och flerfilsanrop.
- **F8:** graceful shutdown (två fynd).
- **F10:** prober och samtidig LibreOffice-omstart (tre fynd).
- **F11:** uppladdningar till `/tmp` (två fynd).
- **F12:** OOM (två fynd).
- **F14:** loggformat och filnamn.
- **F16:** kömätvärden och latenshistogrammet.

Fyndet om att kalkylark saknar taggar är inaktuellt efter `fec62b6`: `.xlsx` blir nu PDF/A-1a, och README är uppdaterad.

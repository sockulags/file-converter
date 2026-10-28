# Runbook

Hur driften felsöker och agerar. Funktionerna beskrivs i [README](../README.md), kapaciteten i
[kapacitet.md](kapacitet.md) och bytet från Gotenberg i [byte-fran-gotenberg.md](byte-fran-gotenberg.md).

Exemplen utgår från namespacet `file-converter` (overlays/prod). Byt mot ert eget.

## Snabbfakta

| | |
|---|---|
| API | port `3000` (Service `file-converter`, port `http`) |
| Hälsa | `GET :3000/health`: `200` när LibreOffice är igång, annars `503`. Används av alla prober |
| Mätvärden | `GET :9090/actuator/prometheus` (port `metrics`) |
| Loggar | JSON (ECS) på stdout. En rad per konvertering från loggern `se.fileconverter.konvertering` |
| Spårnings-id | Svarshuvudet `Gotenberg-Trace`, fältet `traceId` i loggen och i felsvaret |
| Administration | `/admin` på port 3000, bara via port-forward (NetworkPolicy) |
| Larm | `deploy/k8s/components/monitoring/prometheusrule.yaml` |
| Dashboard | Grafana, `file-converter` (`deploy/k8s/components/monitoring/dashboard/`) |

```bash
kubectl -n file-converter get pods -l app=file-converter -o wide
kubectl -n file-converter logs deploy/file-converter --since=15m | grep -v '"level":"DEBUG"'
kubectl -n file-converter port-forward deploy/file-converter 3000 9090
```

## Orsakskoder

Varje fel har `reason` i svaret (`application/problem+json`), i loggraden (`orsak=`) och i
`converter_rejections_total{reason}`. 4xx räknas som utfallet `avvisad`, 5xx som `fel`.

| Orsak | Status | Betyder | Åtgärd | Larm |
|---|---|---|---|---|
| `BAD_REQUEST` | 400 | Anropet saknar filer eller är inte multipart | Klientfel. Kontakta klientens ägare med `traceId` | `FileConverterAvvisadeOkar` |
| `UNSUPPORTED_OPTION` | 400 | Ett fält eller värde stöds inte (t.ex. `pdfa=PDF/A-2b`, `pdfua=true`, `scale≠1`) | Se `ej_stod` på loggraden och `converter_request_fields_total{handling="avvisas"}`. Avgör om klienten ska ändras eller stödet byggas | `FileConverterAvvisatFalt` |
| `UNSUPPORTED_FORMAT` | 400 | Filändelsen känns inte igen av LibreOffice | Klientfel, eller ett format som behöver läggas till | `FileConverterAvvisadeOkar` |
| `DUPLICATE_FILENAME` | 400 | Två filer med samma namn i anropet | Klientfel | |
| `UNSUPPORTED_ROUTE` | 404 | En Gotenberg-route som inte finns här | `converter_unknown_routes_total`. Inför bytet: blockerande. Efter: rollback om klienten inte kan ändras | `FileConverterOkandRoute` |
| `TOO_LARGE` | 413 | Över 100 MB | Höj `SPRING_SERVLET_MULTIPART_MAX_*` bara efter kapacitetsberäkning (minne och `/tmp`) | |
| `ENCRYPTED` | 422 | Krypterad PDF eller lösenordsskyddat dokument | Avvisas alltid. Klienten skickar en okrypterad fil eller `password` (LibreOffice) | |
| `SIGNED` | 422 | Redan signerad PDF; PDF/A-rättningen skulle bryta signaturen | Avvisas alltid. Skicka filen utan `pdfa` | |
| `IMAGE_TOO_LARGE` | 422 | Bild över 40 megapixel | Avvisas alltid. Klienten skalar ner bilden | |
| `UNREADABLE_PDF` | 422 | PDF:en går inte att läsa | Kontrollera filen i testbänken i `/admin` | |
| `UNTAGGED` | 422 | PDF/A-1a begärd men filen saknar taggar (bara strikt läge) | Begär 1b, eller tagga källdokumentet | `FileConverterUnderkandaPdfa` (bästa försök) |
| `TRANSPARENCY` | 422 | Verklig transparens, tillåts inte i PDF/A-1 (strikt läge) | Ändra källan, eller vänta på PDF/A-2 | `FileConverterUnderkandaPdfa` |
| `MISSING_FONT` | 422 | Fonter som inte är inbäddade i en PDF (strikt läge) | Ladda upp fonten eller ett alias i `/admin` om källan är Office/HTML; för PDF:er måste källan bädda in | `FileConverterUnderkandaPdfa` |
| `PDFA_INVALID` | 422 | veraPDF underkänner filen (strikt läge) | `violations` och `converter_pdfa_violations_total{rule}` säger vilka regler | `FileConverterUnderkandaPdfa` |
| `LIBREOFFICE_FAILED` | 422 | LibreOffice kan inte läsa eller exportera dokumentet | Öppna filen i testbänken. Trasig eller ovanlig fil | |
| `MERGE_FAILED` | 422 | Sammanslagningen av PDF:er misslyckades | Testa filerna var för sig | |
| `BUSY` | 503 | Alla platser eller alla LibreOffice-processer upptagna längre än kötiden. `Retry-After: 10` | Se [503 BUSY](#busy) | `FileConverterUpptagen` |
| `LIBREOFFICE_TIMEOUT` | 503 | Konverteringen tog längre än `CONVERTER_OFFICE_TASK_TIMEOUT` (120 s) | Se [LibreOffice](#libreoffice) | `FileConverterLibreOfficeFel` |
| `LIBREOFFICE_CRASHED` | 503 | soffice dog under jobbet, även efter ett nytt försök | Se [LibreOffice](#libreoffice) | `FileConverterLibreOfficeFel` |
| `UNAVAILABLE` | 503 | LibreOffice är avstängt eller stoppat (t.ex. under omstart efter fontändring) | Övergående. Består det: se [Inte redo](#inte-redo) | `FileConverterLibreOfficeFel` |
| `INTERNT_FEL` | 500 | Oväntat fel. Stackspåret ligger i loggen med samma `traceId` | Felanmäl med `traceId` och filen om möjligt | `FileConverterFelkvotHog` |

I bästa försök-läget (standard) ger de PDF/A-relaterade orsakerna inte 422. Filen returneras med
`X-Pdfa-Compliant: false` och orsakerna i `X-Pdfa-Problems`, och utfallet blir `underkand`.

Ett `503` direkt från samtidighetsgränsen (inte från LibreOffice) har ingen `reason` i kroppen, men
räknas också i `converter_busy_total`.

## Följa ett anrop med Gotenberg-Trace

Klienten kan skicka ett eget id i huvudet `Gotenberg-Trace` (bokstäver, siffror, `-_.:`, högst 100
tecken). Annars skapas ett UUID. Id:t kommer tillbaka i svarshuvudet `Gotenberg-Trace`, i felsvarets
`traceId` och i fältet `traceId` på varje loggrad som hör till anropet.

1. Ta id:t från klientens logg eller felsvaret.
2. Sök i loggarna:
   - Loki: `{namespace="file-converter", container="file-converter"} | json | traceId="arende-4711"`
   - Elastic (ECS): `traceId : "arende-4711"`
   - Utan central logg: `kubectl -n file-converter logs -l app=file-converter --since=1h --prefix | grep '"traceId":"arende-4711"'`
3. Raden från `se.fileconverter.konvertering` sammanfattar anropet: `route`, `pdfa`, `utfall`,
   `orsak`, `status`, `ms`, `filer` och `ej_stod` (fält som ignorerades eller avvisades).
   Övriga rader med samma id är varningar och fel under anropet, t.ex. en LibreOffice-krasch.
4. Avvisade och misslyckade anrop syns också under **Statistik** i `/admin`, men bara för den podd du
   är inloggad mot.

## Larm

<a id="felkvot"></a>
### FileConverterFelkvotHog (critical)

Mer än 2 % av anropen ger 5xx i 10 minuter. Är ett rollback-kriterium.

1. Dashboarden, panelen **Avvisningar och fel per orsak**: vilken orsak dominerar?
   `BUSY` → [503 BUSY](#busy). `LIBREOFFICE_*` → [LibreOffice](#libreoffice). `INTERNT_FEL` → loggen
   (`"level":"ERROR"`) med stackspår.
2. En podd eller alla? `sum by (pod) (rate(converter_conversions_total{outcome="fel"}[10m]))`.
   En podd: starta om den (`kubectl delete pod`). Alla: senaste ändringen (image, konfiguration,
   fonter) är trolig orsak; rulla tillbaka den i git.
3. Kvarstår det efter 30 minuter och orsaken är okänd: [rollback till Gotenberg](#rollback).

<a id="underkanda-pdfa"></a>
### FileConverterUnderkandaPdfa

Mer än 5 % av PDF/A-anropen på en route ger en fil som inte klarar valideringen.

- `topk(10, sum by (rule) (increase(converter_pdfa_violations_total[1h])))` visar vilka regler.
- Hämta några exempel i `/admin` → Statistik (filnamn och `traceId`) och kör filerna i testbänken.
- Vanliga orsaker: transparens i Office-dokument (blir 1b), PDF:er utan inbäddade fonter, en font
  eller ett alias som saknas efter en ändring i `/admin`.
- Började det efter en fontändring: återställ med [import](#fonter) av senaste export.

<a id="avvisade"></a>
### FileConverterAvvisadeOkar

Mer än 10 % av anropen avvisas (4xx). Efter bytet från Gotenberg är det oftast ett fält, en
PDF/A-nivå eller ett format som klienten använder men som inte stöds här.
`sum by (reason) (increase(converter_rejections_total[1h]))` och loggradens `ej_stod`.

<a id="falt-och-routes"></a>
### FileConverterAvvisatFalt och FileConverterOkandRoute

En klient skickar ett fält som avvisas, eller anropar en Gotenberg-route som saknas.
Klientens egna fältnamn står på loggraden (`ej_stod=[…]`). Fältnamnet i mätvärdet är `okant` om det
inte finns i Gotenbergs lista. Bestäm med klientens ägare om klienten ska ändras eller om stödet ska
byggas. Drabbar det en viktig klient och kan inte lösas snabbt: [rollback](#rollback).

<a id="latens"></a>
### FileConverterLatensHog

p95 över 20 s för en route i 15 minuter. Histogrammet slutar vid 30 s, så p95 visar aldrig mer.

- Office: köar anropen? Jämför med `FileConverterUpptagen` och CPU per podd. Full CPU: skala upp
  (HPA) eller sänk `CONVERTER_LIMITS_MAX_CONCURRENT`.
- En enskild tung fil syns som `ms=` på loggraden. Tidsgränsen för LibreOffice är 120 s.

<a id="busy"></a>
### FileConverterUpptagen (503 BUSY)

Fler än 10 anrop på 10 minuter fick 503, för att samtidighetsgränsen eller LibreOffice-poolen var full.
Klienten ska försöka igen efter `Retry-After`.

1. Står HPA på max (`FileConverterHpaPaMax`)? Höj `maxReplicas` i overlayn.
2. Är CPU låg men BUSY hög? Då är poolen flaskhalsen, inte CPU: HPA skalar inte. Höj
   `CONVERTER_OFFICE_POOL_SIZE` tillsammans med CPU och minne enligt [kapacitet.md](kapacitet.md),
   eller höj `minReplicas`.
3. Kortvariga toppar som klienterna klarar med omförsök kan accepteras. Höj då tröskeln.

<a id="libreoffice"></a>
### FileConverterLibreOfficeFel

`LIBREOFFICE_TIMEOUT`, `LIBREOFFICE_CRASHED` eller `UNAVAILABLE` mer än 3 gånger på 15 minuter.

- **Tidsgräns:** samma fil igen och igen? Sök loggraden med `orsak=LIBREOFFICE_TIMEOUT` och testa
  filen i testbänken. Många olika filer: CPU-brist, se [503 BUSY](#busy).
- **Krasch:** `kubectl logs` visar `LibreOffice kraschade under konverteringen av …`. Enstaka
  krascher tas om automatiskt. Många: kontrollera minnet (OOM inne i containern dödar soffice först)
  och om en viss fil utlöser det.
- **UNAVAILABLE:** LibreOffice startas om, t.ex. efter en fontändring (alla poddar inom 30 s). Ska
  gå över på en minut. Annars: [Inte redo](#inte-redo).

<a id="inte-redo"></a>
### FileConverterInteRedo och FileConverterNere

En podd har inte varit ready på 10 minuter, eller inga poddar är tillgängliga (critical).

```bash
kubectl -n file-converter describe pod <pod>          # Events, Last State, prober
kubectl -n file-converter logs <pod> | grep -E '"level":"(WARN|ERROR)"'
kubectl -n file-converter port-forward <pod> 3000 & curl -s localhost:3000/health   # imagen saknar curl
```

- `Pending`: inga noder med 2 CPU och 3 GiB lediga, PVC:n saknar en RWX-lagringsklass, eller
  topologySpreadConstraints kräver fler noder.
- LibreOffice startar inte: loggen säger varför. `Kan inte skapa resurskatalogen` betyder att
  volymen inte är skrivbar (fsGroup, se `deploy/k8s/base/pvc.yaml`); tjänsten går ändå.
- `FileConverterNere` och poddarna kommer inte upp inom 10 minuter: [rollback](#rollback).

<a id="skrapas-inte"></a>
### FileConverterSkrapasInte

Prometheus når inte port 9090. Utan mätvärden tystnar alla andra larm. Kontrollera att Prometheus
namespace har etiketten `file-converter/metrics=true` (NetworkPolicy), att PodMonitor finns och att
Prometheus väljer den (etiketter).

<a id="omstarter"></a>
### FileConverterOmstarter och FileConverterOOMKilled

```bash
kubectl -n file-converter get pod <pod> -o jsonpath='{.status.containerStatuses[0].lastState}'
kubectl -n file-converter logs <pod> --previous | tail -50
```

- `OOMKilled`: containerns minne räckte inte. Loggen vid start säger `Minnesbudget: …` och varnar om
  summan är för stor. Höj minnet eller sänk poolen, se [kapacitet.md](kapacitet.md).
- Exit code 3 och `java.lang.OutOfMemoryError` i loggen: heapen tog slut och JVM:en avslutades med
  avsikt (`-XX:+ExitOnOutOfMemoryError`). Ofta ett stort anrop; leta upp det med `traceId` på raderna
  före. Höj `MaxRAMPercentage` bara om containerns minne räcker.
- Liveness-proben: `/health` svarade inte på 2 minuter, oftast LibreOffice som inte kom upp.

<a id="hpa-pa-max"></a>
### FileConverterHpaPaMax

HPA har stått på `maxReplicas` i 30 minuter. Höj `maxReplicas` i overlayn om klustret har plats,
annars se över kapaciteten per podd ([kapacitet.md](kapacitet.md)).

## Omstart

```bash
kubectl -n file-converter rollout restart deployment/file-converter
kubectl -n file-converter rollout status deployment/file-converter
```

Poddarna byts en i taget. Kubernetes tar en podd ur Service innan den stoppas, men pågående
konverteringar i podden avbryts efter grace-perioden (30 s), och klienten får ett nätverksfel. Gör
omstarter när trafiken är låg. En enskild podd: `kubectl delete pod <pod>`. PodDisruptionBudget
skyddar bara mot frivilliga avbrott som nodtömning, inte mot `delete`.

<a id="rollback"></a>
## Rollback

**Till förra versionen av tjänsten:** ändra `newTag` i `deploy/k8s/overlays/prod/kustomization.yaml`
till förra releasen (eller `git revert` på ändringen) och låt Flux synka:
`flux reconcile kustomization file-converter-prod --with-source`.

**Till Gotenberg:** följ [byte-fran-gotenberg.md](byte-fran-gotenberg.md#rollback). Kriterier:

- `FileConverterFelkvotHog` eller `FileConverterNere` som inte går att åtgärda inom 30 minuter.
- Underkända PDF/A eller avvisade fält och routes som stoppar en viktig klient, utan snabb rättning.
- p95-latens som får klienternas egna tidsgränser att slå till.

<a id="fonter"></a>
## Fonter och alias mellan miljöer

Uppladdade fonter och alias ligger på den delade volymen (`/data`). Export ger en zip med allt;
import ersätter allt, men först efter att hela zip-filen kontrollerats.

```bash
# 1. Säkerhetskopia av prod
kubectl -n file-converter port-forward deploy/file-converter 3000 &
curl -fsS -u admin:$PROD_PASS -o prod-$(date +%F).zip http://localhost:3000/admin/api/export

# 2. Export från test
kubectl -n file-converter-test port-forward deploy/file-converter 3001:3000 &
curl -fsS -u admin:$TEST_PASS -o test.zip http://localhost:3001/admin/api/export

# 3. Import i prod
curl -fsS -u admin:$PROD_PASS -H 'X-Requested-With: cli' -F file=@test.zip http://localhost:3000/admin/api/import
```

4. Kontrollera att alla poddar har laddat om (inom 30 s): varje podd loggar
   `… TrueType-fonter och … alias registrerade` och `Startar om LibreOffice för att läsa in ändrade fonter`.
   ```bash
   kubectl -n file-converter logs -l app=file-converter --since=2m --prefix | grep -E 'registrerade|Startar om LibreOffice'
   ```
   `Kunde inte ladda om resurser` betyder att en podd inte fick med ändringen; starta om den.
5. Under omstarten av LibreOffice väntar Office-anrop i alla poddar samtidigt, upp till 2 minuter.
   Gör ändringen när trafiken är låg.
6. Ångra: importera säkerhetskopian från steg 1.

## Uppgradera LibreOffice

LibreOffice kommer från Ubuntus paket i imagen. En ny version kommer med en ny basimage
(Dependabot föreslår digesten) eller en ny release av imagen (`apt-get upgrade` i bygget).

1. Bygg och kör `mvn verify` i CI. Integrationstesterna kräver LibreOffice och validerar varje
   PDF/A-svar med veraPDF.
2. Driftsätt i test och kör `scripts/lasttest.sh` och era egna dokument i testbänken. Jämför
   **Visuell avvikelse** och underkända PDF/A mot förra versionen.
3. Släpp till prod som en vanlig release. Rollback = förra `newTag`.

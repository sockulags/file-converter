# Kapacitet

Hur LibreOffice-poolen, samtidighetsgränsen, CPU, minne, `/tmp` och antalet repliker hänger ihop,
och hur de mäts. Värdena i `deploy/k8s/base/deployment.yaml` är en startkonfiguration. Mät med
lasttestet och de första veckornas drift, och justera sedan.

## Parametrarna

| Parameter | Var | Start | Styr |
|---|---|---|---|
| `CONVERTER_OFFICE_POOL_SIZE` | env | `2` | LibreOffice-processer = samtidiga Office-konverteringar per podd |
| `CONVERTER_LIMITS_MAX_CONCURRENT` | env | `4` | Samtidiga konverteringar per podd, alla routes. Fler köar i `CONVERTER_LIMITS_QUEUE_TIMEOUT` (60 s), sedan `503` |
| `CONVERTER_OFFICE_QUEUE_TIMEOUT` | env | `30s` | Hur länge ett Office-anrop väntar på en ledig process, sedan `503 BUSY` |
| `CONVERTER_OFFICE_PROCESS_MEMORY` | env | `512MB` | Uppskattat minne per LibreOffice-process. Används bara för varningen vid start |
| CPU | `resources` | `2` (request = limit) | |
| `-XX:ActiveProcessorCount` | `JAVA_TOOL_OPTIONS` | `2` | Vad JVM:en tror att den har. Samma som CPU |
| Minne | `resources` | `3Gi` (request = limit) | |
| `-XX:MaxRAMPercentage` | `JAVA_TOOL_OPTIONS` | `40` | Heapens andel av minnet |
| `/tmp` | `emptyDir.sizeLimit` | `4Gi` | Uppladdningar, arbetskataloger och LibreOffice-profiler |
| Repliker | HPA | test 1–2, prod 3–6 | |

## Samband

**Samtidighet.** En Office-konvertering håller en LibreOffice-process, och processen arbetar på i
stort sett en kärna. HTML och PDF körs i JVM:en (openhtmltopdf, PDFBox, veraPDF) och tar också CPU.

- `pool ≈ CPU`. Fler processer än kärnor gör varje konvertering långsammare utan att öka genomflödet.
- `max-concurrent = pool + 2`. Då kan HTML- och PDF-anrop köra medan poolen är full, och några
  Office-anrop står redo i JODConverters kö. Med `0` blir gränsen antalet processorer JVM:en ser,
  vilket utan `ActiveProcessorCount` eller CPU-gräns är nodens alla kärnor (B8 i gap-analysen).
- Ett anrop som inte får plats väntar först i samtidighetsgränsen (60 s) och sedan på en ledig
  LibreOffice-process (30 s). Därefter `503 BUSY` med `Retry-After: 10`.

**Minne.** Containerns minne måste rymma:

```
heap (MaxRAMPercentage × minne)
+ pool × minne per LibreOffice-process (cirka 300–600 MB, mer för stora kalkylark och presentationer)
+ JVM utöver heapen (metaspace, trådar, buffertar, cirka 300 MB)
```

Med startvärdena: 1,2 GiB + 2 × 0,5 GiB + 0,3 GiB ≈ 2,5 GiB av 3 GiB. Tjänsten räknar ut summan vid
start och loggar `Minnesbudget: …`, med en varning när den inte ryms. Heapen behöver i sin tur rymma
de anrop som körs samtidigt: varje anrop håller filen och resultatet i minnet i flera kopior, i värsta
fall `max-concurrent × 3 × filstorlek` (4 × 3 × 100 MB = 1,2 GB vid maxstorleken).

Ändrar ni poolen, ändra minnet: `minne ≥ (pool × 512 MB + 300 MB) / (1 − MaxRAMPercentage)`.
Pool 3 ger cirka 3,1 GB → `3584Mi`; pool 4 cirka 3,9 GB → `4Gi`.

**Request = limit.** Både CPU och minne har samma request och limit (QoS Guaranteed). Podden vräks
då sist när noden får ont om minne, och schemaläggaren reserverar det podden faktiskt använder under
last. Vill ni hellre slippa CPU-strypning kan CPU-gränsen tas bort; podden blir då Burstable, men
minnet är fortfarande skyddat eftersom request = limit. Behåll `ActiveProcessorCount` i så fall.

**`/tmp`.** Uppladdningen skrivs till `/tmp` innan samtidighetsgränsen (F11), och arbetskatalogen
och LibreOffice skriver bredvid. Räkna med `(max-concurrent + köande anrop) × 3 × filstorlek`. 4 GiB
räcker för cirka tio samtidiga anrop med maxstorleken. Överskrids `sizeLimit` vräks podden.

**Repliker.** Med genomflödet `λ` (anrop/s i toppen) och medeltiden `W` (s per anrop) är antalet
samtidiga anrop `λ × W` (Littles lag). Antal poddar:

```
repliker = ceil(λ × W / max-concurrent) + 1     (en extra för nodtömning och omstart)
```

Exempel: 50 000 anrop per dag är 0,6 anrop/s i snitt. Med en topp på 10 gånger snittet (6 anrop/s)
och 2 s per anrop blir det 12 samtidiga anrop: `ceil(12 / 4) + 1 = 4` poddar. Startvärdena (3–6)
täcker det, men `W` måste mätas med era egna dokument.

## HPA

`deploy/k8s/base/hpa.yaml` skalar på CPU (70 % av request) med `behavior`:

- **Upp** direkt, högst 2 poddar per minut. En ny podd tar 30–60 s innan LibreOffice är igång, så
  HPA måste reagera tidigt.
- **Ner** efter 10 minuters stabilisering, en podd varannan minut. En podd som stängs avbryter
  pågående konverteringar.

CPU fungerar som signal eftersom en upptagen LibreOffice-process använder en kärna. Det som HPA inte
ser är kön: anrop som väntar på en process syns inte i CPU, särskilt när poolen är mindre än CPU.
Tecken på att CPU inte räcker som signal:

- `503 BUSY` (`FileConverterUpptagen`) medan CPU ligger under 70 %.
- HPA står på `maxReplicas` (`FileConverterHpaPaMax`).

Då finns tre vägar: sänk CPU-målet (t.ex. 50 %), höj `minReplicas`, eller skala på ett eget mått via
KEDA eller Prometheus Adapter, t.ex. `sum(rate(converter_busy_total[2m]))` eller antalet pågående
anrop när det mätvärdet finns (F16 i gap-analysen).

`minReplicas: 3` i prod gör att en podd kan tömmas (PodDisruptionBudget) och en krascha utan att
allt hamnar på en enda podd. `topologySpreadConstraints` sprider poddarna över noder (krav) och
zoner (om möjligt).

## Lasttest

`scripts/lasttest.sh` skickar en blandning av Word, Excel, PowerPoint, HTML och PDF från
testfilerna med curl och xargs, och skriver statuskoder och latens per route. Inga andra beroenden.

```bash
kubectl -n file-converter-test port-forward svc/file-converter 3000 &

# Snitt: 1 anrop/s i 15 minuter
ANTAL=900 TAKT=1 PARALLELLT=10 scripts/lasttest.sh http://localhost:3000
# Topp: 6 anrop/s i 15 minuter
ANTAL=5400 TAKT=6 PARALLELLT=40 scripts/lasttest.sh http://localhost:3000
# Överlast: så fort det går, för att se var 503 börjar
ANTAL=500 PARALLELLT=40 scripts/lasttest.sh http://localhost:3000
```

Port-forward går till en podd i taget. För att testa flera poddar och HPA, kör skriptet från en
podd i klustret mot `http://file-converter.<namespace>:3000` (namespacet måste ha etiketten
`file-converter/klient=true`).

Under testet, följ dashboarden: anrop per utfall, `503 BUSY`, p95 per route, CPU och containerminne
per podd, och repliker. Efteråt:

1. `W` = medeltiden per anrop (panelen **Medeltid per route**). Räkna om antalet repliker.
2. Toppen för containerminnet jämfört med gränsen. Över 85 %: höj minnet.
3. Kom 503 innan CPU nådde 70 %? Se HPA ovan.
4. Sätt larmens trösklar (`prometheusrule.yaml`) efter vad ni såg: p95 och mängden 503.

Testfilerna är små och syntetiska. Upprepa med ett urval av riktiga dokument (lägg dem i en egen
katalog och peka ut den med `FIXTURES=`, med samma filnamn som i skriptet, eller ändra `BLANDNING`).

### Mätt lokalt

En container med 2 CPU och 3 GiB (som manifestet), skrivskyddat rootfs, PDF/A-1b, testfilerna
(20–40 kB), oktober 2026. Värden på en delad maskin, så se dem som en storleksordning:

| Parallellt | Anrop | Anrop/s | p95 Office | p95 HTML | p95 PDF | CPU | Minne (topp) |
|---|---|---|---|---|---|---|---|
| 4 | 150 | 7,7 | 1,0 s | 0,5 s | 0,4 s | 2,0 kärnor | 870 MiB |
| 8 | 120 | 7,1 | 1,6 s | 1,0 s | 1,1 s | 2,0 kärnor | 850 MiB |

Med 8 samtidiga mot gränsen 4 köade anropen utan 503. Genomflödet begränsas av CPU: dubbelt så
många samtidiga anrop gav längre svarstid men inte fler anrop per sekund.

## Startkonfiguration

| | Test | Prod |
|---|---|---|
| Repliker (HPA) | 1–2 | 3–6 |
| CPU (request = limit) | 2 | 2 |
| Minne (request = limit) | 3Gi | 3Gi |
| Pool | 2 | 2 |
| max-concurrent | 4 | 4 |
| `/tmp` | 4Gi | 4Gi |

Ger i prod 3 × 4 = 12 samtidiga anrop, och upp till 24 vid `maxReplicas: 6`.

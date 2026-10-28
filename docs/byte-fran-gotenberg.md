# Byte från Gotenberg

Plan för att flytta trafiken från Gotenberg till file-converter och tillbaka. Bytet är direkt, utan
skuggkörning, men med en rollback som är en ändring i git. Klienterna ändras inte: de anropar samma
Service-namn och samma port (3000) som förut.

Namnen nedan är exempel: Gotenberg körs som Service `gotenberg` i namespacet `dokument`, och
file-converter i `file-converter` (overlays/prod). Byt mot era egna.

## Förutsättningar

Allt detta ska vara klart innan bytet.

- [ ] **Blockerare i gap-analysen** (`docs/gap-analys.md`, avsnittet Blockerar produktion) är
      åtgärdade eller medvetet accepterade, särskilt PDF/A-nivåer (B2) och Chromium-mallar (B3).
- [ ] **Imagen** är publicerad och `overlays/prod` pekar på en release (`newTag: vX.Y.Z`).
- [ ] **Prod är driftsatt** och frisk: alla poddar ready, `/health` svarar 200, dashboarden visar data.
- [ ] **Larmen** i `components/monitoring` är laddade i Prometheus och når rätt mottagare. Testa
      genom att t.ex. skala testmiljön till 0 och vänta på `FileConverterNere`.
- [ ] **Klienternas namespaces är märkta**, annars stoppar NetworkPolicy deras anrop:
      ```bash
      kubectl label namespace <klientens namespace> file-converter/klient=true
      ```
      Lista vilka namespaces som anropar Gotenberg i dag (Gotenbergs loggar, ingress- eller
      service mesh-loggar, eller klienternas konfiguration).
- [ ] **Fonter och alias** är importerade i prod (export från test, import i prod, se
      [runbook.md](runbook.md#fonter)).
- [ ] **Lasttestet** är kört i test med riktiga dokument, och antalet repliker är räknat
      ([kapacitet.md](kapacitet.md)).
- [ ] **Klienternas tidsgränser** är kända och längre än p95 för deras route. `503 BUSY` med
      `Retry-After` måste tålas, som hos Gotenberg.
- [ ] **Gotenberg finns kvar** och kan ta trafik igen: Deployment med minst en replik.

## Inventering av vad klienterna skickar

Mätvärdena finns bara där file-converter tar emot trafik. Peka därför klienternas testmiljöer mot
file-converter i test i minst en vecka före bytet, och kör frågorna där (och i prod direkt efter
bytet):

```promql
# Fält som klienterna skickar men som inte stöds, per route
sum by (route, field, handling) (increase(converter_request_fields_total{handling!="stods"}[7d]))
# Routes som saknas här
sum by (route, method) (increase(converter_unknown_routes_total[7d]))
# PDF/A-nivåer som begärs (ogiltig = avvisas med 400)
sum by (route, pdfa) (increase(converter_conversions_total{route!="testbank"}[7d]))
# Filtyper
sum by (route, extension) (increase(converter_request_files_total[7d]))
```

Allt med `handling="avvisas"`, alla okända routes och `pdfa="ogiltig"` är klienter som får 400 eller
404 efter bytet. Varje sådant fynd åtgärdas eller godkänns före bytet. `ignoreras` betyder att fältet
saknar verkan här; kontrollera att resultatet ändå blir det klienten väntar sig. Klientens egna
fältnamn står på loggraden (`ej_stod`).

## Hur trafiken flyttas

Välj ett av sätten. Båda behåller Service-namnet och port 3000, så klienterna märker inget, och båda
är en ändring i git som Flux tillämpar.

### A. Gotenbergs Service pekar om till file-converter (ExternalName)

Om file-converter körs i ett eget namespace. Ersätt Gotenbergs Service i dess namespace med:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: gotenberg
  namespace: dokument
spec:
  type: ExternalName
  externalName: file-converter.file-converter.svc.cluster.local
  ports:
    - name: http
      port: 3000
```

`gotenberg.dokument.svc` blir då ett DNS-alias för file-converter. Klienterna ansluter direkt till
file-converters poddar, så NetworkPolicy ser klientens namespace (som ska vara märkt), inte `dokument`.
Lägg till en Service med ett annat namn (t.ex. `gotenberg-reserv`) som pekar på Gotenbergs poddar,
så att Gotenberg kan nås och kontrolleras under reservperioden.

### B. Samma namespace, ny selektor

Om file-converter driftsätts i Gotenbergs namespace (sätt `namespace:` i overlayn). Byt selektorn i
Gotenbergs Service:

```yaml
spec:
  selector:
    app: file-converter     # tidigare app: gotenberg (eller vad er Service väljer)
  ports:
    - name: http
      port: 3000
      targetPort: 3000
```

Service-IP:n är densamma, och bytet slår igenom när endpoints har uppdaterats (sekunder).
NetworkPolicy i `deploy/k8s/base/networkpolicy.yaml` gäller då i det namespacet.

### Befintliga anslutningar

Klienter med keep-alive behåller sina öppna anslutningar till Gotenbergs poddar efter bytet, och
DNS-svar cachas (JVM:er cachar normalt i 30 s). För att alla ska byta inom några minuter: starta om
Gotenberg efter bytet (`kubectl -n dokument rollout restart deployment/gotenberg`), så stängs de
gamla anslutningarna och klienterna ansluter på nytt. Gotenberg finns kvar som reserv.

## Bytet

1. **Dagen före:** kontrollera förutsättningarna ovan. Meddela klienternas ägare tidpunkten och
   hur de rapporterar fel (med `Gotenberg-Trace`).
2. **Vid låg trafik:** slå ihop ändringen av Gotenbergs Service och låt Flux synka
   (`flux reconcile kustomization <namn> --with-source`). Starta om Gotenberg enligt ovan.
3. **Första timmen:** följ dashboarden. Anrop per utfall och route ska motsvara Gotenbergs normala
   trafik. Kör inventeringsfrågorna med `[1h]`. Gå igenom varje avvisat fält och varje okänd route.
4. **Första dygnet:** läs larmen och dashboarden morgon och eftermiddag. Gå igenom
   **Statistik** i `/admin` (avvisade och misslyckade anrop med filnamn) för varje podd.
5. **Reservperioden (2–4 veckor):** Gotenberg körs med en replik och tar ingen trafik. Ta bort
   Gotenberg först när inga rollback-kriterier har uppfyllts under perioden och inventeringen är tom.

<a id="rollback"></a>
## Rollback

**Kriterier.** Rulla tillbaka när något av följande gäller och inte kan åtgärdas inom 30 minuter:

| Kriterium | Larm |
|---|---|
| Mer än 2 % av anropen ger 5xx i 10 minuter | `FileConverterFelkvotHog` (critical) |
| Inga tillgängliga poddar | `FileConverterNere` (critical) |
| En viktig klient får 400 eller 404 för ett fält, en PDF/A-nivå eller en route | `FileConverterAvvisatFalt`, `FileConverterOkandRoute`, `FileConverterAvvisadeOkar` |
| PDF/A-filer underkänns där Gotenberg levererade godkända | `FileConverterUnderkandaPdfa` |
| p95 överskrider klienternas tidsgränser | `FileConverterLatensHog` |
| Fel i resultatet som klienterna rapporterar (layout, fonter) och som inte kan rättas snabbt | – |

**Steg.**

1. `git revert` på ändringen av Gotenbergs Service och synka Flux. Har Gotenberg skalats ner: skala
   upp först (`kubectl -n dokument scale deployment/gotenberg --replicas=<normalt>`) och vänta på ready.
2. Starta om file-converter (`kubectl -n file-converter rollout restart deployment/file-converter`)
   så att klienternas öppna anslutningar stängs.
3. Kontrollera att trafiken har flyttat: `converter_conversions_total` slutar öka, och Gotenbergs
   egna loggar eller mätvärden visar anrop.
4. Spara underlaget: `traceId`, loggrader och exempelfiler för det som utlöste rollbacken. Rätta, testa
   i testmiljön och planera ett nytt byte.

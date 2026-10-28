#!/usr/bin/env bash
# Enkelt lasttest med curl och xargs, utan andra beroenden. Skickar en blandning av Word, Excel,
# PowerPoint, HTML och PDF från testfilerna och skriver statuskoder och latens per route.
#
#   scripts/lasttest.sh [URL]                      # standard http://localhost:3000
#   ANTAL=900 PARALLELLT=10 TAKT=1 scripts/lasttest.sh http://localhost:3000
#
# ANTAL       antal anrop (standard 100)
# PARALLELLT  högst så många anrop samtidigt (standard 4)
# TAKT        anrop per sekund som skickas in, 0 = så fort det går (standard 0).
#             Med TAKT > 0 hålls takten så länge det finns lediga platser i PARALLELLT.
# PDFA        pdfa-fältet, tomt = utan PDF/A (standard PDF/A-1b)
# FIXTURES    katalog med testfilerna (standard src/test/resources/fixtures)
#
# Varje anrop får Gotenberg-Trace lasttest-<nummer>, så att det går att hitta i loggarna.
# Se docs/kapacitet.md för hur resultatet används.
set -euo pipefail

URL=${1:-http://localhost:3000}
ANTAL=${ANTAL:-100}
PARALLELLT=${PARALLELLT:-4}
TAKT=${TAKT:-0}
export PDFA=${PDFA-PDF/A-1b}
export FIXTURES=${FIXTURES:-$(cd "$(dirname "$0")/.." && pwd)/src/test/resources/fixtures}
export URL

# Route och fil per anrop. Blandningen upprepas tills ANTAL är nått.
BLANDNING=(
  "libreoffice/convert beslut.docx"
  "libreoffice/convert avgifter.xlsx"
  "libreoffice/convert presentation.pptx"
  "libreoffice/convert beslut.docx"
  "chromium/convert/html mall.html"
  "pdfengines/convert chromium-tagged.pdf"
)

anrop() {
  local nummer=$1 route=$2 fil=$3 namn=$3
  if [ "$route" = chromium/convert/html ]; then namn=index.html; fi
  local args=(-s -o /dev/null --max-time 300 -w "%{http_code} %{time_total} $route\n"
    -H "Gotenberg-Trace: lasttest-$nummer" -F "files=@$FIXTURES/$fil;filename=$namn")
  if [ -n "$PDFA" ]; then args+=(-F "pdfa=$PDFA"); fi
  # Vid nätverksfel skriver curl ändå raden, med status 000.
  curl "${args[@]}" "$URL/forms/$route" || true
}
export -f anrop

for fil in $(printf '%s\n' "${BLANDNING[@]}" | cut -d' ' -f2 | sort -u); do
  [ -f "$FIXTURES/$fil" ] || { echo "Saknar $FIXTURES/$fil" >&2; exit 2; }
done

resultat=$(mktemp)
trap 'rm -f "$resultat"' EXIT

echo "Lasttest mot $URL: $ANTAL anrop, högst $PARALLELLT samtidigt, takt ${TAKT}/s, pdfa=${PDFA:-ingen}" >&2
start=$(date +%s.%N)
intervall=$(awk -v t="$TAKT" 'BEGIN { print (t > 0) ? 1 / t : 0 }')
for ((i = 0; i < ANTAL; i++)); do
  echo "$i ${BLANDNING[$((i % ${#BLANDNING[@]}))]}"
  if [ "$intervall" != 0 ]; then sleep "$intervall"; fi
done | xargs -L 1 -P "$PARALLELLT" bash -c 'anrop "$@"' _ > "$resultat"
slut=$(date +%s.%N)

echo
echo "Statuskoder:"
awk '{ print $1 }' "$resultat" | sort | uniq -c | sort -rn

echo
echo "Latens per route i sekunder (alla svar):"
printf '%-24s %6s %8s %8s %8s %8s\n' route antal p50 p95 p99 max
sort -k3,3 -k2,2n "$resultat" | awk '
  function skriv(r,   p50, p95, p99) {
    p50 = t[int(n * 0.50 + 0.999) - 1]; p95 = t[int(n * 0.95 + 0.999) - 1]; p99 = t[int(n * 0.99 + 0.999) - 1]
    printf "%-24s %6d %8.2f %8.2f %8.2f %8.2f\n", r, n, p50, p95, p99, t[n - 1]
  }
  $3 != route { if (n) skriv(route); route = $3; n = 0 }
  { t[n++] = $2 }
  END { if (n) skriv(route) }'

awk -v start="$start" -v slut="$slut" -v antal="$ANTAL" \
  'BEGIN { s = slut - start; printf "\n%d anrop på %.1f s: %.2f anrop/s\n", antal, s, antal / s }'

# Annat än 2xx och 503 (överlast, som klienten ska försöka igen på) räknas som fel.
fel=$(awk '$1 !~ /^2/ && $1 != 503' "$resultat" | wc -l)
if [ "$fel" -gt 0 ]; then
  echo "$fel anrop fick fel (varken 2xx eller 503)" >&2
  exit 1
fi

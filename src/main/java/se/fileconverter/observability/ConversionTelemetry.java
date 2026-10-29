package se.fileconverter.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.config.ConverterProperties.Logging.FileNames;

/**
 * Mätvärden för konverteringar (Prometheus via {@code /actuator/prometheus}) och en sammanställning
 * för administrationens statistikflik, inklusive de senaste felen.
 *
 * <ul>
 *   <li>{@code converter_conversions_total{route,pdfa,outcome}}</li>
 *   <li>{@code converter_rejections_total{reason}}: varför filer avvisas</li>
 *   <li>{@code converter_pdfa_violations_total{rule}}: vilka PDF/A-regler som bryts</li>
 *   <li>{@code converter_conversion_duration_seconds{route,outcome}}: histogram upp till 5 min</li>
 *   <li>{@code converter_busy_total{route}}: anrop som fick 503 för att instansen eller LibreOffice var full</li>
 *   <li>{@code converter_queue_wait_seconds{route}}: hur länge anrop väntade på en plats</li>
 *   <li>{@code converter_timeouts_total{stage}}: konverteringar som avbröts av en tidsgräns</li>
 *   <li>{@code converter_request_fields_total{route,field,handling}}: vilka fält och Gotenberg-huvuden klienterna skickar</li>
 *   <li>{@code converter_request_files_total{route,extension}}: vilka filtyper klienterna skickar</li>
 *   <li>{@code converter_unknown_routes_total{route,method}}: anrop till Gotenberg-routes som inte finns här</li>
 *   <li>{@code converter_engine_selected_total{engine,reason}}: vilken motor HTML-mallar renderas med, och varför</li>
 * </ul>
 */
@Component
public class ConversionTelemetry {

    private static final int RECENT_FAILURES = 100;
    /** veraPDF-regel ("6.2.3.3-2: …") eller egen orsakskod från bästa försök-läget ("TRANSPARENCY: …"). */
    private static final Pattern RULE = Pattern.compile("^(\\d+(?:\\.\\d+)*-\\d+|[A-Z_]+):");
    /** Hinkar i sekunder för konverteringstiden. Office-anrop med kö och omexport kan ta flera minuter. */
    private static final Duration[] DURATION_SLOS = seconds(1, 5, 15, 30, 60, 120, 180, 240);
    private static final Duration[] QUEUE_SLOS = seconds(0.1, 1, 5, 15, 30, 60);

    public record Failure(Instant time, String traceId, String route, String pdfa, List<String> files,
            String outcome, String reason, String message, List<String> details) {
    }

    public record RouteStats(String route, Map<String, Long> outcomes, long count, double meanMillis, double maxMillis) {
    }

    public record Stats(String instance, Instant since, List<RouteStats> routes, Map<String, Long> rejections,
            Map<String, Long> violatedRules, long busy, List<Failure> recentFailures) {
    }

    private final MeterRegistry registry;
    private final Instant since = Instant.now();
    private final Map<String, Map<String, AtomicLong>> outcomes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> rejections = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> rules = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Deque<Failure> recent = new ArrayDeque<>();
    private final AtomicLong busy = new AtomicLong();
    private final FileNames fileNames;

    public ConversionTelemetry(MeterRegistry registry) {
        this(registry, FileNames.EXTENSION);
    }

    @Autowired
    public ConversionTelemetry(MeterRegistry registry, ConverterProperties properties) {
        this(registry, properties.fileNames());
    }

    public ConversionTelemetry(MeterRegistry registry, FileNames fileNames) {
        this.registry = registry;
        this.fileNames = fileNames;
    }

    void record(ConversionRecord record, Duration duration) {
        String outcome = record.outcome == null ? "ok" : record.outcome;
        registry.counter("converter.conversions", "route", record.route, "pdfa", record.pdfa, "outcome", outcome).increment();
        timers.computeIfAbsent(record.route + "|" + outcome, key -> Timer.builder("converter.conversion.duration")
                        .tags("route", record.route, "outcome", outcome)
                        .publishPercentileHistogram()
                        .maximumExpectedValue(Duration.ofMinutes(5))
                        .serviceLevelObjectives(DURATION_SLOS)
                        .register(registry))
                .record(duration);
        outcomes.computeIfAbsent(record.route, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(outcome, key -> new AtomicLong()).incrementAndGet();

        record.fields.forEach((field, handling) -> registry.counter("converter.request.fields",
                "route", record.route, "field", field, "handling", handling).increment());
        if (record.engine != null) {
            registry.counter("converter.engine.selected", "engine", record.engine, "reason", record.engineReason).increment();
        }
        for (String extension : record.extensions) {
            registry.counter("converter.request.files", "route", record.route, "extension", extension).increment();
        }
        if (record.reason != null) {
            registry.counter("converter.rejections", "reason", record.reason).increment();
            rejections.computeIfAbsent(record.reason, key -> new AtomicLong()).incrementAndGet();
        }
        for (String detail : record.details) {
            Matcher matcher = RULE.matcher(detail);
            if (matcher.find()) {
                registry.counter("converter.pdfa.violations", "rule", matcher.group(1)).increment();
                rules.computeIfAbsent(detail.length() > 160 ? detail.substring(0, 160) + " …" : detail,
                        key -> new AtomicLong()).incrementAndGet();
            }
        }
        if (!outcome.equals("ok")) {
            synchronized (recent) {
                recent.addFirst(new Failure(Instant.now(), record.traceId, record.route, record.pdfa, files(record),
                        outcome, record.reason, mask(record, record.message),
                        record.details.stream().limit(5).map(detail -> mask(record, detail)).toList()));
                while (recent.size() > RECENT_FAILURES) {
                    recent.removeLast();
                }
            }
        }
    }

    /** Anrop till en Gotenberg-route som inte finns här. */
    public void unknownRoute(String route, String method) {
        registry.counter("converter.unknown.routes", "route", route, "method", method).increment();
    }

    /** @param route routens namn, eller null om anropet inte hunnit registreras */
    public void busy(String route) {
        busy.incrementAndGet();
        Counter.builder("converter.busy")
                .description("Anrop som fick 503 eftersom instansen eller LibreOffice redan körde maximalt antal konverteringar")
                .tag("route", route == null ? "okand" : route)
                .register(registry).increment();
    }

    /** Hur länge ett anrop väntade på en plats i samtidighetsgränsen, även om det sedan fick 503. */
    public void queueWait(String route, Duration wait) {
        Timer.builder("converter.queue.wait")
                .description("Väntan på en plats i samtidighetsgränsen")
                .tag("route", route)
                .serviceLevelObjectives(QUEUE_SLOS)
                .register(registry).record(wait);
    }

    /** En konvertering som avbröts av en tidsgräns. */
    public void timeout(String stage) {
        registry.counter("converter.timeouts", "stage", stage).increment();
    }

    /** Filerna i anropet för loggraden, med namnen som {@code converter.logging.file-names} anger. */
    public List<String> files(ConversionRecord record) {
        return record.uploads.stream()
                .map(upload -> fileNames.describe(upload.name()) + " (" + upload.size() / 1024 + " kB)").toList();
    }

    /** Byter ut klientens filnamn i ett felmeddelande, så att administrationen följer samma inställning som loggen. */
    private String mask(ConversionRecord record, String text) {
        if (text == null || fileNames == FileNames.FULL) {
            return text;
        }
        for (ConversionRecord.Upload upload : record.uploads) {
            if (upload.name() != null && !upload.name().isEmpty()) {
                text = text.replace(upload.name(), fileNames.describe(upload.name()));
            }
        }
        return text;
    }

    /** Sammanställning för den här instansen sedan start. */
    public Stats snapshot() {
        List<RouteStats> routes = new ArrayList<>();
        outcomes.forEach((route, byOutcome) -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            byOutcome.forEach((outcome, count) -> counts.put(outcome, count.get()));
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            double totalMillis = 0;
            double maxMillis = 0;
            for (Map.Entry<String, Timer> timer : timers.entrySet()) {
                if (timer.getKey().startsWith(route + "|")) {
                    totalMillis += timer.getValue().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS);
                    maxMillis = Math.max(maxMillis, timer.getValue().max(java.util.concurrent.TimeUnit.MILLISECONDS));
                }
            }
            routes.add(new RouteStats(route, counts, total, total == 0 ? 0 : totalMillis / total, maxMillis));
        });
        routes.sort(Comparator.comparing(RouteStats::route));
        List<Failure> failures;
        synchronized (recent) {
            failures = List.copyOf(recent);
        }
        return new Stats(instanceName(), since, routes, sorted(rejections), top(sorted(rules), 15),
                busy.get(), failures);
    }

    private static Map<String, Long> sorted(Map<String, AtomicLong> counts) {
        Map<String, Long> result = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().get()));
        return result;
    }

    private static Map<String, Long> top(Map<String, Long> counts, int limit) {
        Map<String, Long> result = new LinkedHashMap<>();
        counts.entrySet().stream().limit(limit).forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static Duration[] seconds(double... values) {
        return java.util.Arrays.stream(values).mapToObj(value -> Duration.ofMillis((long) (value * 1000))).toArray(Duration[]::new);
    }

    private static String instanceName() {
        String host = System.getenv("HOSTNAME");
        return host != null ? host : "lokal";
    }
}

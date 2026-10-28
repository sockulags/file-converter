package se.fileconverter.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ger varje anrop ett spårnings-id och skriver en loggrad plus mätvärden per konvertering.
 *
 * <p>Id:t tas från huvudet {@code Gotenberg-Trace} om klienten skickar ett (samma som Gotenberg),
 * annars skapas ett. Det returneras i samma huvud, står på alla loggrader för anropet och visas i
 * administrationens lista över senaste fel, så att en klients felanmälan kan spåras.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConversionTracingFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "Gotenberg-Trace";
    private static final Pattern SAFE_TRACE = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Logger log = LoggerFactory.getLogger("se.fileconverter.konvertering");

    private final ConversionTelemetry telemetry;

    public ConversionTracingFilter(ConversionTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_HEADER);
        String traceId = incoming != null && SAFE_TRACE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        ConversionRecord record = new ConversionRecord();
        record.traceId = traceId;
        request.setAttribute(ConversionRecord.ATTRIBUTE, record);
        response.setHeader(TRACE_HEADER, traceId);
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (record.route != null) {
                if (record.outcome == null && response.getStatus() >= 400) {
                    record.outcome = response.getStatus() >= 500 ? "fel" : "avvisad";
                }
                Duration duration = Duration.ofNanos(System.nanoTime() - start);
                telemetry.record(record, duration);
                log.info("route={} pdfa={} utfall={} orsak={} status={} ms={} filer={} ej_stod={} motor={}", record.route, record.pdfa,
                        record.outcome == null ? "ok" : record.outcome, record.reason == null ? "-" : record.reason,
                        response.getStatus(), duration.toMillis(), record.files, record.notSupported,
                        record.engine == null ? "-" : record.engine + "/" + record.engineReason);
            }
            MDC.remove("traceId");
        }
    }
}

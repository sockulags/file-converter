package se.fileconverter.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.observability.ConversionRecord;
import se.fileconverter.observability.ConversionTelemetry;

/**
 * Begränsar antalet samtidiga konverteringar per instans. Utan gräns kan några stora filer samtidigt
 * ta slut på minnet, eftersom tjänsten tar emot obegränsat många anrop (virtuella trådar).
 * Anrop över gränsen köar en stund och får sedan 503 med Retry-After, så att klienten kan försöka
 * igen eller Kubernetes skala upp.
 *
 * <p>Gränsen är ett servlet-filter, så att den tas innan multipart-kroppen läses. Köande anrop har
 * då inte skrivit sin uppladdning till disk. En plats hålls tills konverteringen verkligen är klar,
 * även om klienten redan fått 503 för en tidsgräns ({@link Timeouts}), så att gränsen motsvarar
 * det arbete som faktiskt pågår.
 *
 * <p>När instansen stängs av får köande och nya anrop 503 direkt, så att de kan skickas till en annan
 * podd, medan pågående konverteringar får bli klara.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ConcurrencyLimit extends OncePerRequestFilter {

    /** Markerar en handler-metod som en konvertering som räknas mot gränsen och har tidsgränsen för hela anropet. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Limited {
    }

    /** Testbänken i administrationen räknas mot samma gräns som API:t. */
    static final String TESTBANK_PATH = "/admin/api/check";
    private static final String RETRY_AFTER = "10";
    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimit.class);

    private final Semaphore permits;
    private final int max;
    private final int maxQueued;
    private final long queueTimeoutMillis;
    private final AtomicInteger queued = new AtomicInteger();
    private final Set<Thread> waiting = new HashSet<>();
    private final ConversionTelemetry telemetry;
    private final ApplicationContext context;
    private volatile boolean closing;

    public ConcurrencyLimit(ConverterProperties properties, ConversionTelemetry telemetry, MeterRegistry registry,
            ApplicationContext context) {
        this.telemetry = telemetry;
        this.context = context;
        ConverterProperties.Limits limits = properties.limits();
        this.max = limits.maxConcurrent() > 0 ? limits.maxConcurrent() : Runtime.getRuntime().availableProcessors();
        this.maxQueued = limits.maxQueued();
        if (limits.maxConcurrent() > 0) {
            log.info("Högst {} samtidiga konverteringar", max);
        } else {
            // Utan CPU-gräns eller -XX:ActiveProcessorCount blir det nodens alla kärnor. Se docs/kapacitet.md.
            log.warn("CONVERTER_LIMITS_MAX_CONCURRENT är inte satt: högst {} samtidiga konverteringar (antal processorer)", max);
        }
        this.permits = new Semaphore(max, true);
        this.queueTimeoutMillis = limits.queueTimeout().toMillis();
        Gauge.builder("converter.inflight", () -> max - permits.availablePermits())
                .description("Pågående konverteringar, inklusive sådana som fått 503 för en tidsgräns men ännu inte avbrutits")
                .register(registry);
        Gauge.builder("converter.concurrency.limit", () -> max)
                .description("Högsta antal samtidiga konverteringar i instansen").register(registry);
        Gauge.builder("converter.queue.waiting", queued::get)
                .description("Anrop som väntar på en plats").register(registry);
    }

    /**
     * En plats i gränsen. Den lämnas tillbaka när alla som håller den är klara: anropet och de
     * trådar som kör konverteringen (se {@link Timeouts}).
     */
    public final class Permit {

        private final AtomicInteger holders = new AtomicInteger(1);

        void retain() {
            holders.incrementAndGet();
        }

        void release() {
            if (holders.decrementAndGet() == 0) {
                permits.release();
            }
        }
    }

    /** Platsen som den här tråden konverterar under, eller null. */
    static Permit current() {
        return CURRENT.get();
    }

    static void bind(Permit permit) {
        if (permit == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(permit);
        }
    }

    /** Konverteringens route i mätvärden, eller null för anrop som inte begränsas. */
    static String route(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return TESTBANK_PATH.equals(path) ? "testbank" : ConversionController.routeName(path);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || route(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String route = route(request);
        if (!route.equals("testbank")) {
            // Även anrop som avvisas här eller vid multipart-tolkningen ska synas i mätvärden och loggar.
            ConversionRecord.of(request).startIfMissing(route);
        }
        Permit permit;
        try {
            permit = acquire(route);
        } catch (ConversionException e) {
            ConversionRecord.of(request).startIfMissing(route).failed(e.reason().name(), e.getMessage(), List.of());
            if (e.reason() == ConversionException.Reason.BUSY) {
                telemetry.busy(route);
            }
            response.setHeader("Retry-After", RETRY_AFTER);
            ErrorHandler.write(response, e.status(), e.getMessage(), e.reason().name(), request);
            return;
        }
        bind(permit);
        try {
            chain.doFilter(request, response);
        } finally {
            CURRENT.remove();
            permit.release();
        }
    }

    /**
     * Tar en plats, eller kastar 503 om ingen blir ledig inom kötiden, kön är full eller instansen
     * stängs av. Används också av {@link Timeouts} för anrop som av någon anledning inte gått genom filtret.
     */
    Permit acquire(String route) {
        if (closing) {
            throw unavailable();
        }
        if (maxQueued > 0 && permits.availablePermits() == 0 && queued.get() >= maxQueued) {
            telemetry.queueWait(route, Duration.ZERO);
            throw busy();
        }
        long start = System.nanoTime();
        queued.incrementAndGet();
        synchronized (waiting) {
            waiting.add(Thread.currentThread());
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(queueTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Avstängning, se close.
        } finally {
            synchronized (waiting) {
                waiting.remove(Thread.currentThread());
                // Avbrottet kom från close och gäller bara kön. Ligger det kvar stänger NIO anslutningen när svaret skrivs.
                Thread.interrupted();
            }
            queued.decrementAndGet();
            telemetry.queueWait(route, Duration.ofNanos(System.nanoTime() - start));
        }
        if (acquired) {
            return new Permit();
        }
        throw closing ? unavailable() : busy();
    }

    /** Köande anrop får 503 direkt när instansen stängs av, i stället för att vänta på en plats som aldrig hinner bli ledig. */
    @EventListener
    public void close(ContextClosedEvent event) {
        if (event.getApplicationContext() != context) {
            return;
        }
        closing = true;
        synchronized (waiting) {
            waiting.forEach(Thread::interrupt);
        }
    }

    private static ConversionException busy() {
        return new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.BUSY,
                "Tjänsten är upptagen. Försök igen om en stund.", List.of(), null);
    }

    private static ConversionException unavailable() {
        return new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.UNAVAILABLE,
                "Instansen stängs av. Försök igen.", List.of(), null);
    }
}

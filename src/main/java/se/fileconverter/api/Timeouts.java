package se.fileconverter.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.observability.ConversionTelemetry;

/**
 * Tidsgränser för delar av en konvertering och för hela anropet. Utan dem kan en patologisk mall
 * eller PDF hålla en plats i samtidighetsgränsen och CPU utan slut.
 *
 * <p>Arbetet körs i en egen virtuell tråd. När tiden gått ut avbryts tråden och klienten får 503
 * {@code TIMEOUT}. PDFBox, openhtmltopdf och veraPDF reagerar inte alltid på avbrott, så tråden kan
 * fortsätta en stund. Den håller då kvar sin plats i {@link ConcurrencyLimit} tills den är klar, så
 * att gränsen fortfarande motsvarar den CPU som används.
 */
@Component
public class Timeouts {

    public enum Stage {
        REQUEST("anrop", "Konverteringen"),
        HTML("html", "HTML-renderingen"),
        POST_PROCESS("efterbehandling", "Efterbehandlingen av PDF:en"),
        PDFA("pdfa", "PDF/A-normaliseringen och valideringen");

        /** Etikett i mätvärdet {@code converter_timeouts_total{stage}}. */
        final String label;
        final String description;

        Stage(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Timeouts.class);

    private final Map<Stage, Duration> limits;
    private final ConcurrencyLimit concurrency;
    private final ConversionTelemetry telemetry;

    public Timeouts(ConverterProperties properties, ConcurrencyLimit concurrency, ConversionTelemetry telemetry) {
        ConverterProperties.Limits config = properties.limits();
        this.limits = Map.of(Stage.REQUEST, orZero(config.requestTimeout()), Stage.HTML, orZero(config.htmlTimeout()),
                Stage.POST_PROCESS, orZero(config.postProcessTimeout()), Stage.PDFA, orZero(config.pdfaTimeout()));
        this.concurrency = concurrency;
        this.telemetry = telemetry;
    }

    /** Kör {@code work} med tidsgränsen för {@code stage}. */
    public <T> T supply(Stage stage, Supplier<T> work) {
        try {
            return call(stage, work::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Hela anropet. Har anropet av någon anledning inte fått en plats i filtret (t.ex. en sökväg som
     * filtret inte känner igen men Spring ändå leder hit), tas den här i stället.
     */
    <T> T request(String route, Callable<T> work) throws Exception {
        if (ConcurrencyLimit.current() != null) {
            return call(Stage.REQUEST, work);
        }
        ConcurrencyLimit.Permit permit = concurrency.acquire(route);
        ConcurrencyLimit.bind(permit);
        try {
            return call(Stage.REQUEST, work);
        } finally {
            ConcurrencyLimit.bind(null);
            permit.release();
        }
    }

    <T> T call(Stage stage, Callable<T> work) throws Exception {
        Duration limit = limits.get(stage);
        if (limit.isZero() || limit.isNegative()) {
            return work.call();
        }
        ConcurrencyLimit.Permit permit = ConcurrencyLimit.current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        FutureTask<T> task = new FutureTask<>(() -> {
            ConcurrencyLimit.bind(permit);
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            RequestContextHolder.setRequestAttributes(attributes);
            try {
                return work.call();
            } finally {
                RequestContextHolder.resetRequestAttributes();
                MDC.clear();
                ConcurrencyLimit.bind(null);
                if (permit != null) {
                    permit.release();
                }
            }
        });
        if (permit != null) {
            permit.retain();
        }
        Thread.ofVirtual().name("konvertering-" + stage.label).start(task);
        try {
            return task.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            task.cancel(true);
            telemetry.timeout(stage.label);
            log.warn("{} avbröts efter {} s", stage.description, limit.toSeconds());
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.TIMEOUT,
                    stage.description + " blev inte klar inom " + seconds(limit) + ".", List.of(), null);
        } catch (InterruptedException e) {
            // Avbrottet sätts inte tillbaka: på en avbruten tråd stänger NIO anslutningen när felsvaret skrivs.
            task.cancel(true);
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.UNAVAILABLE,
                    "Konverteringen avbröts. Försök igen.", List.of(), e);
        }
    }

    private static String seconds(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + " s" : duration.toMillis() + " ms";
    }

    private static Duration orZero(Duration duration) {
        return duration == null ? Duration.ZERO : duration;
    }
}

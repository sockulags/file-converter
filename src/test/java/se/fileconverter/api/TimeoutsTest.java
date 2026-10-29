package se.fileconverter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.observability.ConversionTelemetry;

class TimeoutsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ConversionTelemetry telemetry = new ConversionTelemetry(registry);
    private final ConverterProperties properties = new ConverterProperties(null, new ConverterProperties.Fonts(List.of(), Map.of()),
            null, null, null, new ConverterProperties.Limits(1, Duration.ofMillis(50), 0, Duration.ofSeconds(30),
                    Duration.ofMillis(100), Duration.ZERO, null));
    private final ConcurrencyLimit limit = new ConcurrencyLimit(properties, telemetry, registry, new StaticApplicationContext());
    private final Timeouts timeouts = new Timeouts(properties, limit, telemetry);

    @Test
    void stageOverItsLimitIs503Timeout() {
        assertThatThrownBy(() -> timeouts.supply(Timeouts.Stage.HTML, () -> {
            sleep(5000);
            return null;
        }))
                .isInstanceOfSatisfying(ConversionException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.reason()).isEqualTo(ConversionException.Reason.TIMEOUT);
                    assertThat(e.getMessage()).startsWith("HTML-renderingen blev inte klar inom 100 ms");
                });
        assertThat(registry.get("converter.timeouts").tag("stage", "html").counter().count()).isEqualTo(1);
    }

    @Test
    void resultsAndErrorsPassThrough() {
        assertThat(timeouts.supply(Timeouts.Stage.HTML, () -> "pdf")).isEqualTo("pdf");
        assertThatThrownBy(() -> timeouts.supply(Timeouts.Stage.HTML, () -> {
            throw ConversionException.badRequest("trasig mall");
        })).isInstanceOf(ConversionException.class).hasMessage("trasig mall");
    }

    @Test
    void zeroMeansNoLimitAndRunsInTheSameThread() {
        Thread caller = Thread.currentThread();
        assertThat(timeouts.supply(Timeouts.Stage.POST_PROCESS, () -> Thread.currentThread() == caller)).isTrue();
        assertThat(timeouts.supply(Timeouts.Stage.PDFA, () -> Thread.currentThread() == caller)).isTrue();
    }

    /** En konvertering som inte reagerar på avbrott håller sin plats tills den verkligen är klar. */
    @Test
    void slotIsHeldUntilAnUninterruptibleConversionFinishes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        limit.doFilter(ConcurrencyLimitTest.request("POST", "/forms/chromium/convert/html"), response, (req, res) -> {
            assertThatThrownBy(() -> timeouts.supply(Timeouts.Stage.HTML, () -> {
                awaitUninterruptibly(release);
                return null;
            })).isInstanceOf(ConversionException.class);
        });

        assertThat(registry.get("converter.inflight").gauge().value()).isEqualTo(1);
        MockHttpServletResponse next = new MockHttpServletResponse();
        limit.doFilter(ConcurrencyLimitTest.request("POST", "/forms/chromium/convert/html"), next, (req, res) -> { });
        assertThat(next.getStatus()).isEqualTo(503);

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registry.get("converter.inflight").gauge().value() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(registry.get("converter.inflight").gauge().value()).isZero();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException e) {
                // Som PDFBox: avbrottet ignoreras.
            }
        }
    }
}

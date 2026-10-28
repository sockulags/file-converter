package se.fileconverter.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.observability.ConversionTelemetry;

/**
 * Gränsen är ett filter som tas innan multipart-kroppen läses (tidigare en interceptor, som kördes
 * först efter att uppladdningen skrivits till disk). Testerna går därför genom filtret.
 */
class ConcurrencyLimitTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StaticApplicationContext context = new StaticApplicationContext();

    private ConcurrencyLimit limit(int max, Duration queueTimeout, int maxQueued) {
        return new ConcurrencyLimit(new ConverterProperties(null, new ConverterProperties.Fonts(List.of(), Map.of()), null, null,
                null, new ConverterProperties.Limits(max, queueTimeout, maxQueued, null, null, null, null)),
                new ConversionTelemetry(registry), registry, context);
    }

    static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    @Test
    void requestsOverTheLimitGet503UntilASlotIsFree() throws Exception {
        ConcurrencyLimit limit = limit(1, Duration.ofMillis(50), 0);
        MockHttpServletResponse busy = new MockHttpServletResponse();

        limit.doFilter(request("POST", "/forms/libreoffice/convert"), new MockHttpServletResponse(),
                (req, res) -> limit.doFilter(request("POST", "/forms/libreoffice/convert"), busy, (a, b) -> {
                    throw new AssertionError("Andra anropet ska inte släppas igenom");
                }));

        assertThat(busy.getStatus()).isEqualTo(503);
        assertThat(busy.getHeader("Retry-After")).isNotNull();
        assertThat(busy.getContentType()).startsWith("application/problem+json");
        assertThat(busy.getContentAsString()).contains("\"reason\":\"BUSY\"");
        assertThat(registry.get("converter.busy").tag("route", "libreoffice").counter().count()).isEqualTo(1);
        assertThat(registry.get("converter.queue.wait").tag("route", "libreoffice").timer().count()).isEqualTo(2);

        AtomicBoolean passed = new AtomicBoolean();
        limit.doFilter(request("POST", "/forms/libreoffice/convert"), new MockHttpServletResponse(), (req, res) -> passed.set(true));
        assertThat(passed).isTrue();
        assertThat(registry.get("converter.inflight").gauge().value()).isZero();
    }

    @Test
    void onlyConversionsAreLimited() throws Exception {
        ConcurrencyLimit limit = limit(1, Duration.ofMillis(50), 0);
        AtomicBoolean health = new AtomicBoolean();
        AtomicBoolean unknown = new AtomicBoolean();

        limit.doFilter(request("POST", "/forms/pdfengines/merge"), new MockHttpServletResponse(), (req, res) -> {
            limit.doFilter(request("GET", "/health"), new MockHttpServletResponse(), (a, b) -> health.set(true));
            limit.doFilter(request("POST", "/forms/nagot/annat"), new MockHttpServletResponse(), (a, b) -> unknown.set(true));
        });

        assertThat(health).isTrue();
        assertThat(unknown).isTrue();
    }

    @Test
    void fullQueueGives503WithoutWaiting() throws Exception {
        ConcurrencyLimit limit = limit(1, Duration.ofSeconds(10), 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> filter(limit, (req, res) -> {
            running.countDown();
            await(release);
        }));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        Thread queued = Thread.ofVirtual().start(() -> filter(limit, (req, res) -> { }));
        while (registry.get("converter.queue.waiting").gauge().value() < 1) {
            Thread.onSpinWait();
        }

        long start = System.nanoTime();
        MockHttpServletResponse response = new MockHttpServletResponse();
        limit.doFilter(request("POST", "/forms/chromium/convert/html"), response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        release.countDown();
        first.join();
        queued.join();
    }

    @Test
    void queuedRequestsGet503WhenTheInstanceShutsDown() throws Exception {
        ConcurrencyLimit limit = limit(1, Duration.ofSeconds(30), 0);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> filter(limit, (req, res) -> {
            running.countDown();
            await(release);
        }));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        MockHttpServletResponse queuedResponse = new MockHttpServletResponse();
        Thread queued = Thread.ofVirtual().start(() -> {
            try {
                limit.doFilter(request("POST", "/forms/pdfengines/convert"), queuedResponse, (req, res) -> { });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        while (registry.get("converter.queue.waiting").gauge().value() < 1) {
            Thread.onSpinWait();
        }

        limit.close(new ContextClosedEvent(context));
        queued.join(5000);

        assertThat(queuedResponse.getStatus()).isEqualTo(503);
        assertThat(queuedResponse.getContentAsString()).contains("UNAVAILABLE");
        MockHttpServletResponse late = new MockHttpServletResponse();
        limit.doFilter(request("POST", "/forms/pdfengines/convert"), late, (req, res) -> { });
        assertThat(late.getStatus()).isEqualTo(503);
        // Pågående konverteringar får bli klara.
        release.countDown();
        first.join();
        assertThat(registry.get("converter.inflight").gauge().value()).isZero();
    }

    private static void filter(ConcurrencyLimit limit, FilterChain chain) {
        try {
            limit.doFilter(request("POST", "/forms/libreoffice/convert"), new MockHttpServletResponse(), chain);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

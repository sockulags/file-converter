package se.fileconverter.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import se.fileconverter.config.ConverterProperties;

class ConcurrencyLimitTest {

    static class Handlers {
        @ConcurrencyLimit.Limited
        public void convert() {
        }

        public void health() {
        }
    }

    private final ConcurrencyLimit limit = new ConcurrencyLimit(new ConverterProperties(null,
            new ConverterProperties.Fonts(List.of(), Map.of()), null, null, null,
            new ConverterProperties.Limits(1, Duration.ofMillis(50))),
            new se.fileconverter.observability.ConversionTelemetry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

    @Test
    void requestsOverTheLimitGet503UntilASlotIsFree() throws Exception {
        HandlerMethod convert = new HandlerMethod(new Handlers(), Handlers.class.getMethod("convert"));
        MockHttpServletRequest first = new MockHttpServletRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        assertThat(limit.preHandle(first, firstResponse, convert)).isTrue();

        MockHttpServletResponse busy = new MockHttpServletResponse();
        assertThat(limit.preHandle(new MockHttpServletRequest(), busy, convert)).isFalse();
        assertThat(busy.getStatus()).isEqualTo(503);
        assertThat(busy.getHeader("Retry-After")).isNotNull();

        limit.afterCompletion(first, firstResponse, convert, null);
        assertThat(limit.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), convert)).isTrue();
    }

    @Test
    void unmarkedHandlersAreNotLimited() throws Exception {
        HandlerMethod convert = new HandlerMethod(new Handlers(), Handlers.class.getMethod("convert"));
        HandlerMethod health = new HandlerMethod(new Handlers(), Handlers.class.getMethod("health"));
        limit.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), convert);

        assertThat(limit.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), health)).isTrue();
    }
}

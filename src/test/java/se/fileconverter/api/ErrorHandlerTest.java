package se.fileconverter.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import se.fileconverter.observability.ConversionTelemetry;

class ErrorHandlerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ErrorHandler handler = new ErrorHandler(new ConversionTelemetry(registry));

    @Test
    void busyLibreOfficeAsksTheClientToRetry() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ProblemDetail problem = handler.conversion(new ConversionException(HttpStatus.SERVICE_UNAVAILABLE,
                ConversionException.Reason.BUSY, "Upptaget", List.of(), null), new MockHttpServletRequest(), response);

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getProperties()).containsEntry("reason", "BUSY");
        assertThat(response.getHeader("Retry-After")).isEqualTo("10");
        assertThat(registry.get("converter.busy").counter().count()).isEqualTo(1);
    }

    @Test
    void timeoutIsUnavailableButNotARetryHint() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ProblemDetail problem = handler.conversion(new ConversionException(HttpStatus.SERVICE_UNAVAILABLE,
                ConversionException.Reason.LIBREOFFICE_TIMEOUT, "Tidsgräns", List.of(), null), new MockHttpServletRequest(), response);

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNull();
    }
}

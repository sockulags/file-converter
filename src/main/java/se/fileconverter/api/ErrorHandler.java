package se.fileconverter.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import se.fileconverter.observability.ConversionRecord;
import se.fileconverter.observability.ConversionTelemetry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fel som problem+json (RFC 9457). {@code reason} är en maskinläsbar orsak, {@code violations} listar
 * vad som hindrar PDF/A och {@code traceId} är anropets spårnings-id.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConversionTelemetry telemetry;

    public ErrorHandler(ConversionTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @ExceptionHandler(ConversionException.class)
    ProblemDetail conversion(ConversionException e, HttpServletRequest request, HttpServletResponse response) {
        if (e.status().is5xxServerError()) {
            ConversionRecord.of(request).failed(e.reason().name(), e.getMessage(), e.details());
        } else {
            ConversionRecord.of(request).rejected(e.reason().name(), e.getMessage(), e.details());
        }
        if (e.reason() == ConversionException.Reason.BUSY) {
            telemetry.busy(ConversionRecord.of(request).route());
            response.setHeader("Retry-After", "10");
        }
        ProblemDetail problem = problem(e.status(), e.getMessage(), e.reason().name(), request);
        if (!e.details().isEmpty()) {
            problem.setProperty("violations", e.details());
        }
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e, HttpServletRequest request) {
        ConversionRecord.of(request).rejected(ConversionException.Reason.UNSUPPORTED_OPTION.name(), e.getMessage(), List.of());
        return problem(HttpStatus.BAD_REQUEST, e.getMessage(), ConversionException.Reason.UNSUPPORTED_OPTION.name(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        ConversionRecord.of(request).startIfMissing(ConversionController.routeName(request.getRequestURI()))
                .rejected("TOO_LARGE", "Filen är för stor.", List.of());
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Filen är för stor.", "TOO_LARGE", request);
    }

    @ExceptionHandler(MultipartException.class)
    ProblemDetail notMultipart(MultipartException e, HttpServletRequest request) {
        return notMultipart(request);
    }

    /** Spring kastar IllegalStateException när en konverterings-route anropas utan multipart-innehåll. */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException e, HttpServletRequest request) {
        String type = request.getContentType();
        if (ConversionController.routeName(request.getRequestURI()) != null
                && (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/"))) {
            return notMultipart(request);
        }
        return unexpected(e, request);
    }

    private ProblemDetail notMultipart(HttpServletRequest request) {
        ConversionRecord.of(request).startIfMissing(ConversionController.routeName(request.getRequestURI()))
                .rejected("BAD_REQUEST", "Anropet är inte multipart/form-data.", List.of());
        return problem(HttpStatus.BAD_REQUEST, "Anropet måste vara multipart/form-data med minst en fil.", "BAD_REQUEST", request);
    }

    /** Oväntade fel: logga med spårnings-id och svara utan interna detaljer. */
    @ExceptionHandler(RuntimeException.class)
    ProblemDetail unexpected(RuntimeException e, HttpServletRequest request) {
        if (e instanceof ErrorResponse known) {
            return known.getBody();
        }
        ConversionRecord record = ConversionRecord.of(request);
        record.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        log.error("Oväntat fel i konverteringen", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ett internt fel inträffade. Ange spårnings-id " + record.traceId() + " vid felanmälan.", "INTERNT_FEL", request);
    }

    /**
     * Samma svar som {@link #problem}, för fel som uppstår i ett filter innan Spring MVC tar över,
     * t.ex. när samtidighetsgränsen är nådd.
     */
    static void write(HttpServletResponse response, HttpStatusCode status, String detail, String reason,
            HttpServletRequest request) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", HttpStatus.valueOf(status.value()).getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("reason", reason);
        String traceId = ConversionRecord.of(request).traceId();
        if (traceId != null) {
            body.put("traceId", traceId);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(body));
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String reason, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("reason", reason);
        String traceId = ConversionRecord.of(request).traceId();
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}

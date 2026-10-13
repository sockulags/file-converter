package se.fileconverter.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import se.fileconverter.observability.ConversionRecord;

/**
 * Fel som problem+json (RFC 9457). {@code reason} är en maskinläsbar orsak, {@code violations} listar
 * vad som hindrar PDF/A och {@code traceId} är anropets spårnings-id.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ConversionException.class)
    ProblemDetail conversion(ConversionException e, HttpServletRequest request) {
        ConversionRecord.of(request).rejected(e.reason().name(), e.getMessage(), e.details());
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
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Filen är för stor.", "TOO_LARGE", request);
    }

    @ExceptionHandler(MultipartException.class)
    ProblemDetail notMultipart(MultipartException e, HttpServletRequest request) {
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

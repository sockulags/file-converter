package se.fileconverter.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Fel som problem+json (RFC 9457). {@code violations} listar vad som hindrar PDF/A.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ConversionException.class)
    ProblemDetail conversion(ConversionException e) {
        log.info("Konvertering avvisad ({}): {} {}", e.status().value(), e.getMessage(), e.details());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        if (!e.details().isEmpty()) {
            problem.setProperty("violations", e.details());
        }
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "Filen är för stor.");
    }

    @ExceptionHandler(MultipartException.class)
    ProblemDetail notMultipart(MultipartException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Anropet måste vara multipart/form-data med minst en fil.");
    }
}

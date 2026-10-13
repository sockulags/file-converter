package se.fileconverter.api;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Fel som ska nå klienten med en begriplig förklaring, t.ex. varför en fil inte kan bli PDF/A.
 */
public class ConversionException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> details;

    public ConversionException(HttpStatus status, String message) {
        this(status, message, List.of(), null);
    }

    public ConversionException(HttpStatus status, String message, List<String> details) {
        this(status, message, details, null);
    }

    public ConversionException(HttpStatus status, String message, List<String> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.details = List.copyOf(details);
    }

    public static ConversionException badRequest(String message) {
        return new ConversionException(HttpStatus.BAD_REQUEST, message);
    }

    public static ConversionException unprocessable(String message, List<String> details) {
        return new ConversionException(HttpStatus.UNPROCESSABLE_CONTENT, message, details);
    }

    public HttpStatus status() {
        return status;
    }

    public List<String> details() {
        return details;
    }
}

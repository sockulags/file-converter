package se.fileconverter.api;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Fel som ska nå klienten med en begriplig förklaring, t.ex. varför en fil inte kan bli PDF/A.
 *
 * <p>{@link Reason} är en maskinläsbar orsak som räknas i mätvärdena och visas i svaret
 * ({@code reason}), så att man i drift kan se vad som oftast stoppar filer.
 */
public class ConversionException extends RuntimeException {

    public enum Reason {
        BAD_REQUEST,
        UNSUPPORTED_FORMAT,
        UNSUPPORTED_OPTION,
        DUPLICATE_FILENAME,
        ENCRYPTED,
        SIGNED,
        UNTAGGED,
        TRANSPARENCY,
        MISSING_FONT,
        IMAGE_TOO_LARGE,
        PDFA_INVALID,
        UNREADABLE_PDF,
        LIBREOFFICE_FAILED,
        LIBREOFFICE_TIMEOUT,
        LIBREOFFICE_CRASHED,
        BUSY,
        TIMEOUT,
        MERGE_FAILED,
        UNAVAILABLE,
        UNSUPPORTED_ROUTE
    }

    private final HttpStatus status;
    private final Reason reason;
    private final List<String> details;

    public ConversionException(HttpStatus status, Reason reason, String message, List<String> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.reason = reason;
        this.details = List.copyOf(details);
    }

    public static ConversionException badRequest(String message) {
        return badRequest(Reason.BAD_REQUEST, message);
    }

    public static ConversionException badRequest(Reason reason, String message) {
        return new ConversionException(HttpStatus.BAD_REQUEST, reason, message, List.of(), null);
    }

    public static ConversionException unprocessable(Reason reason, String message, List<String> details) {
        return new ConversionException(HttpStatus.UNPROCESSABLE_CONTENT, reason, message, details, null);
    }

    public HttpStatus status() {
        return status;
    }

    public Reason reason() {
        return reason;
    }

    public List<String> details() {
        return details;
    }
}

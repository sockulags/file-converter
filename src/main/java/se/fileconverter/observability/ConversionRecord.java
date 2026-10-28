package se.fileconverter.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Vad som hände i ett konverteringsanrop. Fylls i av controllern och felhanteraren och
 * rapporteras av {@link ConversionTracingFilter} när anropet är klart.
 */
public final class ConversionRecord {

    static final String ATTRIBUTE = ConversionRecord.class.getName();

    String traceId;
    String route;
    String pdfa = "ingen";
    List<String> files = List.of();
    String outcome;
    String reason;
    String message;
    List<String> details = List.of();

    /** Postens anrop, eller en tom post som inte rapporteras om anropet inte gått genom filtret. */
    public static ConversionRecord of(HttpServletRequest request) {
        Object record = request.getAttribute(ATTRIBUTE);
        return record instanceof ConversionRecord existing ? existing : new ConversionRecord();
    }

    public String traceId() {
        return traceId;
    }

    public ConversionRecord start(String route, String pdfa, List<String> files) {
        this.route = route;
        this.pdfa = pdfa == null ? "ingen" : pdfa;
        this.files = List.copyOf(files);
        return this;
    }

    /** @param compliant om resultatet klarade PDF/A-valideringen; null när PDF/A inte begärts */
    public void succeeded(Boolean compliant, List<String> violations) {
        this.outcome = compliant == null || compliant ? "ok" : "underkand";
        this.details = List.copyOf(violations);
    }

    public void rejected(String reason, String message, List<String> details) {
        this.outcome = "avvisad";
        this.reason = reason;
        this.message = message;
        this.details = List.copyOf(details);
    }

    public void failed(String message) {
        failed("INTERNT_FEL", message, List.of());
    }

    /** Fel som inte beror på filen, t.ex. överlast (5xx). Räknas som utfallet "fel" så att larm på fel ser dem. */
    public void failed(String reason, String message, List<String> details) {
        this.outcome = "fel";
        this.reason = reason;
        this.message = message;
        this.details = List.copyOf(details);
    }
}

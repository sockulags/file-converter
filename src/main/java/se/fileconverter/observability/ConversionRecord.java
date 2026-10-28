package se.fileconverter.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;
import se.fileconverter.pdfa.PdfaLevel;

/**
 * Vad som hände i ett konverteringsanrop. Fylls i av controllern och felhanteraren och
 * rapporteras av {@link ConversionTracingFilter} när anropet är klart.
 */
public final class ConversionRecord {

    static final String ATTRIBUTE = ConversionRecord.class.getName();

    /** Filändelser som får bli etiketter i mätvärden. Övriga räknas som "annan". */
    private static final Set<String> EXTENSIONS = Set.of("docx", "doc", "docm", "dotx", "odt", "rtf", "txt", "xlsx", "xls",
            "xlsm", "ods", "csv", "pptx", "ppt", "ppsx", "odp", "pdf", "html", "htm", "css", "png", "jpg", "jpeg", "gif",
            "svg", "bmp", "tif", "tiff", "webp", "ttf", "otf", "woff", "woff2", "md");

    String traceId;
    String route;
    String pdfa = "ingen";
    List<String> files = List.of();
    List<String> extensions = List.of();
    Map<String, String> fields = Map.of();
    List<String> notSupported = List.of();
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

    /** @param pdfa klientens råa värde. Bara giltiga nivåer blir etiketter, annars blir det "ogiltig". */
    public ConversionRecord start(String route, String pdfa, List<MultipartFile> uploads) {
        this.route = route;
        this.pdfa = pdfaLabel(pdfa);
        this.files = uploads.stream().map(file -> file.getOriginalFilename() + " (" + file.getSize() / 1024 + " kB)").toList();
        this.extensions = uploads.stream().map(file -> extension(file.getOriginalFilename())).toList();
        return this;
    }

    /** Används när anropet avvisas innan controllern hunnit registrera det, t.ex. för stora filer. */
    public ConversionRecord startIfMissing(String route) {
        if (this.route == null) {
            this.route = route;
        }
        return this;
    }

    /**
     * @param fields fält och Gotenberg-huvuden med namn som är säkra som etiketter, och hur de hanterades
     * @param notSupported fält som ignorerades eller avvisades, med klientens egna namn, för loggraden
     */
    public void fields(Map<String, String> fields, List<String> notSupported) {
        this.fields = Map.copyOf(fields);
        this.notSupported = List.copyOf(notSupported);
    }

    static String pdfaLabel(String pdfa) {
        if (pdfa == null || pdfa.isBlank()) {
            return "ingen";
        }
        try {
            return PdfaLevel.parse(pdfa).map(PdfaLevel::label).orElse("ingen");
        } catch (IllegalArgumentException e) {
            return "ogiltig";
        }
    }

    static String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSIONS.contains(extension) ? extension : extension.isEmpty() ? "ingen" : "annan";
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

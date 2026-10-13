package se.fileconverter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import se.fileconverter.office.OfficeOptions;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tolkar Gotenberg 8:s formulärfält för de routes tjänsten har.
 *
 * <p>Fält delas in i tre grupper: de som stöds, de som saknar betydelse här och ignoreras
 * (listas i svarshuvudet {@code X-Converter-Ignored-Fields}), och de som inte kan göras rätt och
 * därför avvisas med 400 i stället för att ge ett annat resultat än klienten väntar sig.
 */
public final class GotenbergOptions {

    public static final String IGNORED_HEADER = "X-Converter-Ignored-Fields";

    /** Chromium-fält som kräver JavaScript, nätverk eller webbläsarbeteende. HTML-renderaren har inget av det. */
    private static final Set<String> CHROMIUM_NO_EFFECT = Set.of("waitDelay", "waitForExpression", "waitForSelector",
            "emulatedMediaFeatures", "cookies", "userAgent", "extraHttpHeaders", "failOnHttpStatusCodes",
            "failOnResourceHttpStatusCodes", "failOnResourceLoadingFailed", "failOnConsoleExceptions",
            "skipNetworkIdleEvent", "omitBackground", "generateTaggedPdf", "ignoreResourceHttpStatusDomains");

    /** Gotenbergs LibreOffice-fält som motsvarar en exportinställning i LibreOffice. */
    private static final Map<String, String> LIBREOFFICE_BOOLEANS = Map.ofEntries(
            Map.entry("exportFormFields", "ExportFormFields"),
            Map.entry("allowDuplicateFieldNames", "AllowDuplicateFieldNames"),
            Map.entry("exportBookmarks", "ExportBookmarks"),
            Map.entry("exportBookmarksToPdfDestination", "ExportBookmarksToPDFDestination"),
            Map.entry("exportPlaceholders", "ExportPlaceholders"),
            Map.entry("exportNotes", "ExportNotes"),
            Map.entry("exportNotesPages", "ExportNotesPages"),
            Map.entry("exportOnlyNotesPages", "ExportOnlyNotesPages"),
            Map.entry("exportNotesInMargin", "ExportNotesInMargin"),
            Map.entry("convertOooTargetToPdfTarget", "ConvertOOoTargetToPDFTarget"),
            Map.entry("exportLinksRelativeFsys", "ExportLinksRelativeFsys"),
            Map.entry("exportHiddenSlides", "ExportHiddenSlides"),
            Map.entry("skipEmptyPages", "IsSkipEmptyPages"),
            Map.entry("singlePageSheets", "SinglePageSheets"),
            Map.entry("losslessImageCompression", "UseLosslessCompression"),
            Map.entry("reduceImageResolution", "ReduceImageResolution"));

    private static final Set<String> COMMON = Set.of("pdfa", "pdfaStrict", "pdfua", "metadata", "flatten");
    private static final Set<String> LIBREOFFICE = Set.of("merge", "landscape", "password", "nativePageRanges",
            "quality", "maxImageResolution", "addOriginalDocumentAsStream");
    private static final Set<String> CHROMIUM = Set.of("paperWidth", "paperHeight", "marginTop", "marginBottom",
            "marginLeft", "marginRight", "landscape", "preferCssPageSize", "printBackground",
            "generateDocumentOutline", "nativePageRanges", "singlePage", "scale", "emulatedMediaType");
    private static final Set<String> UNSUPPORTED_EVERYWHERE = Set.of("splitMode", "splitSpan", "splitUnify", "downloadFrom");

    private static final Pattern PAGE_RANGES = Pattern.compile("\\s*\\d+(\\s*-\\s*\\d+)?(\\s*,\\s*\\d+(\\s*-\\s*\\d+)?)*\\s*");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public enum Route { LIBREOFFICE, CHROMIUM, PDFENGINES }

    private final HttpServletRequest request;
    private final List<String> ignored = new ArrayList<>();

    private GotenbergOptions(HttpServletRequest request) {
        this.request = request;
    }

    /** Kontrollerar fält och huvuden för routen. Avvisar med 400 det som inte kan göras rätt. */
    public static GotenbergOptions parse(HttpServletRequest request, Route route) {
        GotenbergOptions options = new GotenbergOptions(request);
        options.validate(route);
        return options;
    }

    private void validate(Route route) {
        if (request.getHeader("Gotenberg-Webhook-Url") != null) {
            throw unsupported("Webhooks (asynkron konvertering) stöds inte. Anropa utan Gotenberg-Webhook-Url och vänta på svaret.");
        }
        if (bool("pdfua", false)) {
            throw unsupported("PDF/UA stöds inte. Använd pdfa=PDF/A-1a, som också kräver taggad, tillgänglig PDF.");
        }
        for (String field : Collections.list(request.getParameterNames())) {
            if (UNSUPPORTED_EVERYWHERE.contains(field)) {
                throw unsupported("Fältet '" + field + "' stöds inte.");
            }
            boolean known = COMMON.contains(field)
                    || (route == Route.LIBREOFFICE && (LIBREOFFICE.contains(field) || LIBREOFFICE_BOOLEANS.containsKey(field)))
                    || (route == Route.CHROMIUM && CHROMIUM.contains(field))
                    || (route == Route.PDFENGINES && field.equals("merge"));
            if (!known) {
                ignored.add(field);
            }
        }
        if (route == Route.CHROMIUM) {
            if (bool("singlePage", false)) {
                throw unsupported("singlePage stöds inte. Ange sidstorlek med paperWidth/paperHeight eller @page.");
            }
            String scale = request.getParameter("scale");
            if (scale != null && !scale.isBlank() && Double.parseDouble(scale.trim()) != 1.0) {
                throw unsupported("scale stöds inte. Skala mallen med CSS (t.ex. font-size) i stället.");
            }
            String media = request.getParameter("emulatedMediaType");
            if (media != null && media.trim().equalsIgnoreCase("screen")) {
                throw unsupported("emulatedMediaType=screen stöds inte. Mallen renderas alltid som utskrift (@media print).");
            }
        }
        if (route == Route.LIBREOFFICE && bool("addOriginalDocumentAsStream", false) && request.getParameter("pdfa") != null
                && !request.getParameter("pdfa").isBlank()) {
            throw unsupported("addOriginalDocumentAsStream kan inte kombineras med PDF/A-1, som inte tillåter bifogade filer.");
        }
    }

    /** Fält som togs emot men inte påverkar resultatet. */
    public List<String> ignoredFields() {
        return List.copyOf(new TreeSet<>(ignored));
    }

    public boolean flatten() {
        return bool("flatten", false);
    }

    /** Gotenbergs {@code metadata}: JSON med t.ex. Title, Author, Subject, Keywords, Creator, Producer. */
    public Map<String, Object> metadata() {
        String json = request.getParameter("metadata");
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(json, LinkedHashMap.class);
            for (String date : List.of("CreationDate", "ModDate")) {
                if (parsed.get(date) instanceof String value) {
                    try {
                        OffsetDateTime.parse(value);
                    } catch (DateTimeParseException e) {
                        throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION,
                                "metadata." + date + " måste vara ett datum enligt ISO 8601, t.ex. 2026-10-13T12:00:00+02:00.");
                    }
                }
            }
            return parsed;
        } catch (JacksonException | ClassCastException e) {
            throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION, "Fältet 'metadata' måste vara ett JSON-objekt.");
        }
    }

    public String pageRanges() {
        String ranges = request.getParameter("nativePageRanges");
        if (ranges == null || ranges.isBlank()) {
            return null;
        }
        if (!PAGE_RANGES.matcher(ranges).matches()) {
            throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION,
                    "nativePageRanges ska ha formen '1-5, 8, 11-13'.");
        }
        return ranges.replace(" ", "");
    }

    /** LibreOffice-inställningar från Gotenbergs fält. */
    public OfficeOptions office(boolean pdfA) {
        Map<String, Object> filterData = new LinkedHashMap<>();
        LIBREOFFICE_BOOLEANS.forEach((field, key) -> {
            String value = request.getParameter(field);
            if (value != null && !value.isBlank()) {
                filterData.put(key, Boolean.parseBoolean(value.trim()));
            }
        });
        Integer quality = integer("quality", 1, 100);
        if (quality != null) {
            filterData.put("Quality", quality);
        }
        Integer resolution = integer("maxImageResolution", 75, 1200);
        if (resolution != null) {
            filterData.put("MaxImageResolution", resolution);
        }
        if (bool("addOriginalDocumentAsStream", false)) {
            filterData.put("IsAddStream", true);
        }
        String ranges = pageRanges();
        if (ranges != null) {
            filterData.put("PageRange", ranges);
        }
        String password = request.getParameter("password");
        return new OfficeOptions(pdfA, filterData, bool("landscape", false),
                password == null || password.isEmpty() ? null : password);
    }

    public boolean bool(String field, boolean fallback) {
        String value = request.getParameter(field);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private Integer integer(String field, int min, int max) {
        String value = request.getParameter(field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION,
                    "Fältet '" + field + "' ska vara ett heltal mellan " + min + " och " + max + ".");
        }
    }

    private static ConversionException unsupported(String message) {
        return ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION, message);
    }
}

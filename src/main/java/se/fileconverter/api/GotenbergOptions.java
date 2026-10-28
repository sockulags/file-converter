package se.fileconverter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import se.fileconverter.chromium.ChromiumConverter;
import se.fileconverter.observability.ConversionRecord;
import se.fileconverter.office.OfficeOptions;
import se.fileconverter.pdfa.PdfaLevel;
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

    /** Chromium-fält som bara fungerar när mallen renderas med Chromium, inte med openhtmltopdf. */
    private static final Set<String> CHROMIUM_ONLY = Set.of("waitDelay", "waitForExpression", "waitForSelector",
            "emulatedMediaFeatures", "userAgent", "failOnHttpStatusCodes", "failOnResourceHttpStatusCodes",
            "failOnResourceLoadingFailed", "failOnConsoleExceptions", "skipNetworkIdleEvent", "omitBackground",
            "generateTaggedPdf", "ignoreResourceHttpStatusDomains", "singlePage", "scale", "emulatedMediaType");
    /** Fält som bara påverkar nätverksanrop. Mallar får inte nå nätverket, så de har ingen verkan. */
    private static final Set<String> NO_NETWORK = Set.of("cookies", "extraHttpHeaders");

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
            "generateDocumentOutline", "nativePageRanges", "converterEngine");
    private static final Set<String> UNSUPPORTED_EVERYWHERE = Set.of("splitMode", "splitSpan", "splitUnify", "downloadFrom");
    /** Övriga Gotenberg 8-fält som saknar motsvarighet här. De ignoreras men räknas med sitt namn. */
    private static final Set<String> OTHER_GOTENBERG = Set.of("updateIndexes", "userPassword", "ownerPassword");
    /** Gotenbergs huvuden. Andra huvuden som börjar på {@code Gotenberg-} räknas som okända. */
    private static final Set<String> GOTENBERG_HEADERS = Set.of("Gotenberg-Trace", "Gotenberg-Output-Filename",
            "Gotenberg-Webhook-Url", "Gotenberg-Webhook-Error-Url", "Gotenberg-Webhook-Method",
            "Gotenberg-Webhook-Error-Method", "Gotenberg-Webhook-Extra-Http-Headers", "Gotenberg-Webhook-Events-Url");
    private static final Set<String> SUPPORTED_HEADERS = Set.of("Gotenberg-Trace", "Gotenberg-Output-Filename");

    /** Namn som får bli etiketter i mätvärden. Allt annat räknas som {@link #UNKNOWN}, så att klienter inte kan skapa nya serier. */
    private static final Set<String> KNOWN_NAMES = knownNames();
    static final String UNKNOWN = "okant";

    private static final Pattern LENGTH = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(in|cm|mm|pt|px|pc)?");
    private static final Pattern GO_DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h)");
    private static final Pattern PAGE_RANGES = Pattern.compile("\\s*\\d+(\\s*-\\s*\\d+)?(\\s*,\\s*\\d+(\\s*-\\s*\\d+)?)*\\s*");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public enum Route { LIBREOFFICE, CHROMIUM, PDFENGINES }

    private final HttpServletRequest request;
    private final List<String> ignored = new ArrayList<>();
    /** Varje fält och Gotenberg-huvud i anropet och hur det hanterades: stods, ignoreras eller avvisas. */
    private final Map<String, String> usage = new LinkedHashMap<>();

    private GotenbergOptions(HttpServletRequest request) {
        this.request = request;
    }

    /** Kontrollerar fält och huvuden för routen. Avvisar med 400 det som inte kan göras rätt. */
    public static GotenbergOptions parse(HttpServletRequest request, Route route) {
        GotenbergOptions options = new GotenbergOptions(request);
        try {
            options.validate(route);
        } finally {
            // Även avvisade anrop räknas, annars syns inte vilka fält som stoppade klienterna.
            ConversionRecord.of(request).fields(options.fieldUsage(), options.notSupported());
        }
        return options;
    }

    private void validate(Route route) {
        for (String header : Collections.list(request.getHeaderNames())) {
            if (header.regionMatches(true, 0, "Gotenberg-", 0, 10)) {
                String name = GOTENBERG_HEADERS.stream().filter(header::equalsIgnoreCase).findFirst().orElse(header);
                usage.put(name, SUPPORTED_HEADERS.contains(name) ? "stods" : "ignoreras");
            }
        }
        for (String field : Collections.list(request.getParameterNames())) {
            boolean known = COMMON.contains(field)
                    || (route == Route.LIBREOFFICE && (LIBREOFFICE.contains(field) || LIBREOFFICE_BOOLEANS.containsKey(field)))
                    || (route == Route.CHROMIUM && (CHROMIUM.contains(field) || CHROMIUM_ONLY.contains(field)))
                    || (route == Route.PDFENGINES && field.equals("merge"));
            usage.put(field, known ? "stods" : "ignoreras");
        }
        if (request.getHeader("Gotenberg-Webhook-Url") != null) {
            throw unsupported("Gotenberg-Webhook-Url",
                    "Webhooks (asynkron konvertering) stöds inte. Anropa utan Gotenberg-Webhook-Url och vänta på svaret.");
        }
        if (bool("pdfua", false)) {
            throw unsupported("pdfua", "PDF/UA stöds inte. Använd pdfa=PDF/A-1a, som också kräver taggad, tillgänglig PDF.");
        }
        for (String field : usage.keySet()) {
            if (UNSUPPORTED_EVERYWHERE.contains(field)) {
                throw unsupported(field, "Fältet '" + field + "' stöds inte.");
            }
        }
        usage.forEach((field, handling) -> {
            if (handling.equals("ignoreras") && !field.regionMatches(true, 0, "Gotenberg-", 0, 10)) {
                ignored.add(field);
            }
        });
        if (route == Route.LIBREOFFICE && bool("addOriginalDocumentAsStream", false) && request.getParameter("pdfa") != null
                && !request.getParameter("pdfa").isBlank()) {
            throw unsupported("addOriginalDocumentAsStream",
                    "addOriginalDocumentAsStream kan inte kombineras med PDF/A: PDF/A-1 och 2 tillåter inte bifogade filer av alla slag.");
        }
    }

    /**
     * När mallen renderas med openhtmltopdf (vald med converterEngine): avvisa det som inte kan göras
     * rätt utan webbläsare, och lista övriga Chromium-fält som ignorerade.
     */
    public void forOpenHtmlToPdf() {
        try {
            if (bool("singlePage", false)) {
                throw unsupported("singlePage", "singlePage kräver Chromium. Ange sidstorlek med paperWidth/paperHeight eller @page.");
            }
            String scale = request.getParameter("scale");
            if (scale != null && !scale.isBlank() && Double.parseDouble(scale.trim()) != 1.0) {
                throw unsupported("scale", "scale kräver Chromium. Skala mallen med CSS (t.ex. font-size) i stället.");
            }
            String media = request.getParameter("emulatedMediaType");
            if (media != null && media.trim().equalsIgnoreCase("screen")) {
                throw unsupported("emulatedMediaType", "emulatedMediaType=screen kräver Chromium.");
            }
            usage.forEach((field, handling) -> {
                if (CHROMIUM_ONLY.contains(field)) {
                    ignored.add(field);
                }
            });
        } finally {
            ConversionRecord.of(request).fields(fieldUsage(), notSupported());
        }
    }

    /**
     * Chromium-inställningar med Gotenberg 8:s standardvärden: Letter (8,5 × 11 tum), marginaler
     * 0,39 tum, skala 1, utan bakgrunder.
     *
     * @param tagged taggad PDF, för PDF/A
     */
    public ChromiumConverter.Options chromium(boolean tagged) {
        double scale = number("scale", 1.0);
        if (scale < 0.1 || scale > 2.0) {
            throw unsupported("scale", "scale ska vara mellan 0.1 och 2.");
        }
        String media = request.getParameter("emulatedMediaType");
        media = media == null || media.isBlank() ? "print" : media.trim().toLowerCase(Locale.ROOT);
        if (!media.equals("print") && !media.equals("screen")) {
            throw unsupported("emulatedMediaType", "emulatedMediaType ska vara print eller screen.");
        }
        String userAgent = request.getParameter("userAgent");
        return new ChromiumConverter.Options(
                inches("paperWidth", 8.5), inches("paperHeight", 11),
                inches("marginTop", 0.39), inches("marginRight", 0.39), inches("marginBottom", 0.39), inches("marginLeft", 0.39),
                bool("landscape", false), bool("preferCssPageSize", false), bool("printBackground", false),
                bool("omitBackground", false), scale, pageRanges(), bool("generateDocumentOutline", false),
                tagged || bool("generateTaggedPdf", false), bool("singlePage", false), duration("waitDelay"),
                text("waitForExpression"), text("waitForSelector"), media, mediaFeatures(),
                userAgent == null || userAgent.isBlank() ? null : userAgent,
                bool("failOnConsoleExceptions", false), bool("failOnResourceLoadingFailed", false),
                statusCodes("failOnResourceHttpStatusCodes"), bool("skipNetworkIdleEvent", false));
    }

    /** Mått i tum. Tal utan enhet är tum, som i Gotenberg. */
    private double inches(String field, double fallback) {
        String value = request.getParameter(field);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Matcher matcher = LENGTH.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw ConversionException.badRequest("Ogiltigt värde för '" + field + "': " + value);
        }
        double number = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2) == null ? "in" : matcher.group(2);
        return switch (unit) {
            case "cm" -> number / 2.54;
            case "mm" -> number / 25.4;
            case "pt" -> number / 72;
            case "px" -> number / 96;
            case "pc" -> number / 6;
            default -> number;
        };
    }

    /** Gotenbergs tidsangivelser, t.ex. "500ms", "2s", "1m". */
    private Duration duration(String field) {
        String value = request.getParameter(field);
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        Matcher matcher = GO_DURATION.matcher(value.trim());
        if (!matcher.matches()) {
            throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION,
                    "Fältet '" + field + "' ska vara en tid som '500ms', '2s' eller '1m'.");
        }
        double number = Double.parseDouble(matcher.group(1));
        long millis = (long) (number * switch (matcher.group(2)) {
            case "ms" -> 1;
            case "s" -> 1_000;
            case "m" -> 60_000;
            default -> 3_600_000;
        });
        return Duration.ofMillis(millis);
    }

    private double number(String field, double fallback) {
        String value = request.getParameter(field);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION, "Fältet '" + field + "' ska vara ett tal.");
        }
    }

    private String text(String field) {
        String value = request.getParameter(field);
        return value == null || value.isBlank() ? null : value;
    }

    /** Gotenbergs emulatedMediaFeatures: [{"name": "prefers-color-scheme", "value": "dark"}]. */
    private List<Map<String, String>> mediaFeatures() {
        String json = request.getParameter("emulatedMediaFeatures");
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> features = new ArrayList<>();
            for (Object item : JSON.readValue(json, List.class)) {
                if (item instanceof Map<?, ?> map && map.get("name") instanceof String name) {
                    features.add(Map.of("name", name, "value", String.valueOf(map.get("value") == null ? "" : map.get("value"))));
                }
            }
            return features;
        } catch (JacksonException e) {
            throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION,
                    "emulatedMediaFeatures ska vara en JSON-lista med name och value.");
        }
    }

    /** Gotenbergs statuskodslistor, t.ex. [404, 499]: 499 betyder alla 400-koder. */
    private List<Integer> statusCodes(String field) {
        String json = request.getParameter(field);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Integer> codes = new ArrayList<>();
            for (Object item : JSON.readValue(json, List.class)) {
                codes.add(((Number) item).intValue());
            }
            return codes;
        } catch (JacksonException | ClassCastException e) {
            throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION, field + " ska vara en JSON-lista med statuskoder.");
        }
    }

    /**
     * Fält och Gotenberg-huvuden i anropet och hur de hanterades, med namn som är säkra som etiketter
     * i mätvärden: okända namn blir {@value #UNKNOWN}.
     */
    public Map<String, String> fieldUsage() {
        Map<String, String> result = new LinkedHashMap<>();
        usage.forEach((name, handling) -> result.merge(KNOWN_NAMES.contains(name) ? name : UNKNOWN, handling,
                (a, b) -> a.equals("avvisas") || b.equals("avvisas") ? "avvisas" : a));
        return result;
    }

    /**
     * Fält och huvuden som inte stöds, med klientens egna namn ("waitDelay=ignoreras"), för loggraden.
     * Kontrolltecken byts ut så att ett fältnamn inte kan skapa egna loggrader.
     */
    public List<String> notSupported() {
        return usage.entrySet().stream().filter(entry -> !entry.getValue().equals("stods"))
                .map(entry -> {
                    String name = entry.getKey().replaceAll("\\p{Cntrl}", "?");
                    return (name.length() > 60 ? name.substring(0, 60) : name) + "=" + entry.getValue();
                })
                .sorted().limit(30).toList();
    }

    private static Set<String> knownNames() {
        Set<String> names = new java.util.HashSet<>();
        List.of(COMMON, LIBREOFFICE, LIBREOFFICE_BOOLEANS.keySet(), CHROMIUM, CHROMIUM_ONLY, NO_NETWORK, UNSUPPORTED_EVERYWHERE,
                OTHER_GOTENBERG, GOTENBERG_HEADERS).forEach(names::addAll);
        names.add("merge");
        return Set.copyOf(names);
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
    public OfficeOptions office(PdfaLevel pdfa) {
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
        return new OfficeOptions(pdfa, filterData, bool("landscape", false),
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

    private ConversionException unsupported(String field, String message) {
        usage.put(field, "avvisas");
        return ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION, message);
    }
}

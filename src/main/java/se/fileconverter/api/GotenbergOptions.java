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
import org.springframework.web.multipart.MultipartHttpServletRequest;
import se.fileconverter.observability.ConversionRecord;
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
    /** Övriga Gotenberg 8-fält som saknar motsvarighet här. De ignoreras men räknas med sitt namn. */
    private static final Set<String> OTHER_GOTENBERG = Set.of("updateIndexes");
    /**
     * Fält som skulle ge ett annat resultat än klienten väntar sig om de ignorerades: lösenorden ger en
     * krypterad fil i Gotenberg, här skulle filen bli okrypterad. Avvisas om de har ett värde.
     */
    private static final Set<String> REJECT_IF_PRESENT = Set.of("userPassword", "ownerPassword");
    /** Vattenstämpel och stämpel (Gotenberg 8, även LibreOffice-fälten nativeWatermark*), oavsett exakt fältnamn. */
    private static final List<String> REJECTED_PREFIXES = List.of("watermark", "stamp", "nativeWatermark", "nativeTiledWatermark");
    /** Filfält med särskild betydelse i Gotenberg. Här skulle filerna annars konverteras som dokument. */
    private static final Set<String> REJECTED_FILE_FIELDS = Set.of("embeds", "watermark", "stamp");
    private static final String WEBHOOK_PREFIX = "Gotenberg-Webhook-";
    /** Gotenbergs huvuden. Andra huvuden som börjar på {@code Gotenberg-} räknas som okända. */
    private static final Set<String> GOTENBERG_HEADERS = Set.of("Gotenberg-Trace", "Gotenberg-Output-Filename",
            "Gotenberg-Webhook-Url", "Gotenberg-Webhook-Error-Url", "Gotenberg-Webhook-Method",
            "Gotenberg-Webhook-Error-Method", "Gotenberg-Webhook-Extra-Http-Headers", "Gotenberg-Webhook-Events-Url");
    private static final Set<String> SUPPORTED_HEADERS = Set.of("Gotenberg-Trace", "Gotenberg-Output-Filename");

    /** Namn som får bli etiketter i mätvärden. Allt annat räknas som {@link #UNKNOWN}, så att klienter inte kan skapa nya serier. */
    private static final Set<String> KNOWN_NAMES = knownNames();
    static final String UNKNOWN = "okant";

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
                    || (route == Route.CHROMIUM && CHROMIUM.contains(field))
                    || (route == Route.PDFENGINES && field.equals("merge"));
            usage.put(field, known ? "stods" : "ignoreras");
        }
        if (request instanceof MultipartHttpServletRequest multipart) {
            for (String field : multipart.getMultiFileMap().keySet()) {
                if (REJECTED_FILE_FIELDS.contains(field)) {
                    throw unsupported(field, "Filfältet '" + field + "' stöds inte. "
                            + (field.equals("embeds") ? "PDF/A-1 tillåter inga bifogade filer." : "Vattenstämplar och stämplar stöds inte."));
                }
            }
        }
        List<String> webhooks = usage.keySet().stream().filter(name -> name.regionMatches(true, 0, WEBHOOK_PREFIX, 0, WEBHOOK_PREFIX.length())).toList();
        if (!webhooks.isEmpty()) {
            webhooks.forEach(name -> usage.put(name, "avvisas"));
            throw unsupported(webhooks.getFirst(),
                    "Webhooks (asynkron konvertering) stöds inte. Anropa utan Gotenberg-Webhook-* och vänta på svaret.");
        }
        for (String field : REJECT_IF_PRESENT) {
            String value = request.getParameter(field);
            if (value != null && !value.isBlank()) {
                throw unsupported(field, "Kryptering (userPassword, ownerPassword) stöds inte. Filen skulle annars returneras okrypterad.");
            }
        }
        for (String field : usage.keySet()) {
            if (REJECTED_PREFIXES.stream().anyMatch(prefix -> field.regionMatches(true, 0, prefix, 0, prefix.length()))) {
                throw unsupported(field, "Vattenstämplar och stämplar ('" + field + "') stöds inte.");
            }
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
        if (route == Route.CHROMIUM) {
            if (bool("singlePage", false)) {
                throw unsupported("singlePage", "singlePage stöds inte. Ange sidstorlek med paperWidth/paperHeight eller @page.");
            }
            String scale = request.getParameter("scale");
            if (scale != null && !scale.isBlank() && Double.parseDouble(scale.trim()) != 1.0) {
                throw unsupported("scale", "scale stöds inte. Skala mallen med CSS (t.ex. font-size) i stället.");
            }
            String media = request.getParameter("emulatedMediaType");
            if (media != null && media.trim().equalsIgnoreCase("screen")) {
                throw unsupported("emulatedMediaType",
                        "emulatedMediaType=screen stöds inte. Mallen renderas alltid som utskrift (@media print).");
            }
        }
        if (route == Route.LIBREOFFICE && bool("addOriginalDocumentAsStream", false) && request.getParameter("pdfa") != null
                && !request.getParameter("pdfa").isBlank()) {
            throw unsupported("addOriginalDocumentAsStream",
                    "addOriginalDocumentAsStream kan inte kombineras med PDF/A-1, som inte tillåter bifogade filer.");
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
        List.of(COMMON, LIBREOFFICE, LIBREOFFICE_BOOLEANS.keySet(), CHROMIUM, CHROMIUM_NO_EFFECT, UNSUPPORTED_EVERYWHERE,
                OTHER_GOTENBERG, REJECT_IF_PRESENT, REJECTED_FILE_FIELDS, GOTENBERG_HEADERS).forEach(names::addAll);
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

    private ConversionException unsupported(String field, String message) {
        usage.put(field, "avvisas");
        return ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION, message);
    }
}

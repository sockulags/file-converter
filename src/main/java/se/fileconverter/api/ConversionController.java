package se.fileconverter.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import se.fileconverter.html.HtmlConverter;
import se.fileconverter.office.OfficeConverter;
import se.fileconverter.office.OfficeOptions;
import se.fileconverter.pdf.PdfMerger;
import se.fileconverter.pdf.PdfPostProcessor;
import se.fileconverter.observability.ConversionRecord;
import se.fileconverter.pdfa.PdfaLevel;
import se.fileconverter.pdfa.PdfaNormalizer;
import se.fileconverter.pdfa.PdfaService;
import se.fileconverter.pdfa.PdfaValidator;

/**
 * Samma routes och formulärfält som Gotenberg 8 för de delar som används, så att befintliga
 * klienter kan byta adress utan andra ändringar.
 *
 * <p>Tillägg: fältet {@code pdfaStrict=false} returnerar filen även om valideringen fallerar.
 * Svarshuvudena {@code X-Pdfa-Level} och {@code X-Pdfa-Compliant} anger valideringsresultatet.
 */
@RestController
public class ConversionController {

    private static final Pattern LENGTH = Pattern.compile("\\d+(\\.\\d+)?(in|cm|mm|pt|px|pc)?");
    private static final Comparator<Path> ALPHANUMERIC = Comparator.comparing(p -> p.getFileName().toString());
    private static final Map<String, String> ROUTES = Map.of("/forms/libreoffice/convert", "libreoffice",
            "/forms/chromium/convert/html", "chromium-html", "/forms/pdfengines/convert", "pdfengines-convert",
            "/forms/pdfengines/merge", "pdfengines-merge");

    private final OfficeConverter office;
    private final HtmlConverter html;
    private final PdfMerger merger;
    private final PdfaService pdfa;
    private final PdfPostProcessor postProcessor;
    private final Timeouts timeouts;

    public ConversionController(OfficeConverter office, HtmlConverter html, PdfMerger merger, PdfaService pdfa,
            PdfPostProcessor postProcessor, Timeouts timeouts) {
        this.postProcessor = postProcessor;
        this.timeouts = timeouts;
        this.office = office;
        this.html = html;
        this.merger = merger;
        this.pdfa = pdfa;
    }

    record Output(String name, byte[] pdf, PdfaValidator.Report report) {
    }

    /** 503 tills LibreOffice-poolen är igång, så att Kubernetes inte skickar trafik för tidigt. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        String libreoffice = !office.isEnabled() ? "disabled" : office.isRunning() ? "up" : "down";
        boolean up = !"down".equals(libreoffice);
        return ResponseEntity.status(up ? 200 : 503)
                .body(Map.of("status", up ? "up" : "down", "details", Map.of("libreoffice", libreoffice)));
    }

    @ConcurrencyLimit.Limited
    @PostMapping("/forms/libreoffice/convert")
    public ResponseEntity<byte[]> libreoffice(MultipartHttpServletRequest request) {
        track(request, "libreoffice");
        GotenbergOptions options = GotenbergOptions.parse(request, GotenbergOptions.Route.LIBREOFFICE);
        Optional<PdfaLevel> level = PdfaLevel.parse(request.getParameter("pdfa"));
        Boolean strict = bool(request, "pdfaStrict");
        OfficeOptions officeOptions = options.office(level.isPresent());
        try (Workspace ws = Workspace.from(request)) {
            List<Path> files = ws.files().stream().sorted(ALPHANUMERIC).toList();
            List<Output> outputs = new ArrayList<>();
            for (Path file : files) {
                outputs.add(new Output(baseName(file), office.toPdf(file, officeOptions), null));
            }
            if (Boolean.TRUE.equals(bool(request, "merge")) && outputs.size() > 1) {
                byte[] merged = merger.merge(outputs.stream().map(Output::pdf).toList());
                outputs = List.of(new Output("merged", merged, null));
            }
            return respond(request, options, finish(outputs, options, level, strict));
        }
    }

    @ConcurrencyLimit.Limited
    @PostMapping("/forms/chromium/convert/html")
    public ResponseEntity<byte[]> htmlToPdf(MultipartHttpServletRequest request) {
        track(request, "chromium-html");
        GotenbergOptions options = GotenbergOptions.parse(request, GotenbergOptions.Route.CHROMIUM);
        Optional<PdfaLevel> level = PdfaLevel.parse(request.getParameter("pdfa"));
        Boolean strict = bool(request, "pdfaStrict");
        String ranges = options.pageRanges();
        if (ranges != null && level.isPresent() && level.get().requiresTagging()) {
            throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION,
                    "nativePageRanges kan inte kombineras med PDF/A-1a för mallar: taggstrukturen skulle peka på borttagna sidor.");
        }
        try (Workspace ws = Workspace.from(request)) {
            HtmlConverter.Options htmlOptions = new HtmlConverter.Options(pageCss(request, options.bool("preferCssPageSize", false)),
                    options.bool("printBackground", false), options.bool("generateDocumentOutline", false));
            byte[] pdf = timeouts.supply(Timeouts.Stage.HTML, () -> html.toPdf(ws.dir(), htmlOptions, level));
            if (ranges != null) {
                byte[] rendered = pdf;
                pdf = timeouts.supply(Timeouts.Stage.POST_PROCESS, () -> postProcessor.selectPages(rendered, ranges));
            }
            return respond(request, options, finish(List.of(new Output("index", pdf, null)), options, level, strict));
        }
    }

    @ConcurrencyLimit.Limited
    @PostMapping("/forms/pdfengines/convert")
    public ResponseEntity<byte[]> pdfToPdfa(MultipartHttpServletRequest request) {
        track(request, "pdfengines-convert");
        GotenbergOptions options = GotenbergOptions.parse(request, GotenbergOptions.Route.PDFENGINES);
        PdfaLevel level = PdfaLevel.parse(request.getParameter("pdfa"))
                .orElseThrow(() -> ConversionException.badRequest("Fältet 'pdfa' krävs, t.ex. PDF/A-1a."));
        Boolean strict = bool(request, "pdfaStrict");
        try (Workspace ws = Workspace.from(request)) {
            List<Output> outputs = ws.files().stream().sorted(ALPHANUMERIC)
                    .map(file -> new Output(baseName(file), read(file), null))
                    .toList();
            return respond(request, options, finish(outputs, options, Optional.of(level), strict));
        }
    }

    @ConcurrencyLimit.Limited
    @PostMapping("/forms/pdfengines/merge")
    public ResponseEntity<byte[]> merge(MultipartHttpServletRequest request) {
        track(request, "pdfengines-merge");
        GotenbergOptions options = GotenbergOptions.parse(request, GotenbergOptions.Route.PDFENGINES);
        Optional<PdfaLevel> level = PdfaLevel.parse(request.getParameter("pdfa"));
        Boolean strict = bool(request, "pdfaStrict");
        try (Workspace ws = Workspace.from(request)) {
            List<Path> files = ws.files().stream().sorted(ALPHANUMERIC).toList();
            List<byte[]> pdfs = files.stream().map(ConversionController::read).toList();
            if (level.isPresent() && level.get().requiresTagging() && pdfa.isStrict(strict)) {
                // Efter sammanslagningen syns det inte längre vilken fil som saknade taggar.
                // I bästa försök-läge blir resultatet i stället 1b, med en varning.
                List<String> problems = new ArrayList<>();
                for (int i = 0; i < files.size(); i++) {
                    String file = files.get(i).getFileName().toString();
                    PdfaNormalizer.taggingProblem(pdfs.get(i)).ifPresent(problem -> problems.add(file + ": " + problem));
                }
                if (!problems.isEmpty()) {
                    throw ConversionException.unprocessable(ConversionException.Reason.UNTAGGED,
                            "Alla filer måste vara taggade för att resultatet ska bli " + level.get().label() + ".", problems);
                }
            }
            return respond(request, options, finish(List.of(new Output("merged", merger.merge(pdfs), null)), options, level, strict));
        }
    }

    /** Metadata och plattning av formulär, sedan PDF/A om det begärts. */
    private List<Output> finish(List<Output> outputs, GotenbergOptions options, Optional<PdfaLevel> level, Boolean strict) {
        Map<String, Object> metadata = options.metadata();
        boolean flatten = options.flatten();
        return outputs.stream().map(output -> {
            byte[] pdf = timeouts.supply(Timeouts.Stage.POST_PROCESS, () -> postProcessor.apply(output.pdf(), metadata, flatten));
            if (level.isEmpty()) {
                return new Output(output.name(), pdf, null);
            }
            PdfaService.Result result = timeouts.supply(Timeouts.Stage.PDFA, () -> pdfa.convert(pdf, level.get(), strict));
            return new Output(output.name(), result.pdf(), result.report());
        }).toList();
    }

    /** Routens namn i loggar och mätvärden, eller null för sökvägar som inte är konverteringar. */
    static String routeName(String path) {
        return ROUTES.get(path);
    }

    /** Registrerar anropet för loggning och mätvärden (se {@link ConversionRecord}). */
    private static void track(MultipartHttpServletRequest request, String route) {
        ConversionRecord.of(request).start(route, request.getParameter("pdfa"),
                request.getMultiFileMap().values().stream().flatMap(List::stream).toList());
    }

    private static ResponseEntity<byte[]> respond(MultipartHttpServletRequest request, GotenbergOptions options, List<Output> outputs) {
        List<PdfaValidator.Report> reports = outputs.stream().map(Output::report).filter(Objects::nonNull).toList();
        ConversionRecord.of(request).succeeded(
                reports.isEmpty() ? null : reports.stream().allMatch(PdfaValidator.Report::compliant),
                reports.stream().flatMap(report -> report.violations().stream()).distinct().toList());
        String requestedName = request.getHeader("Gotenberg-Output-Filename");
        HttpHeaders headers = new HttpHeaders();
        if (!options.ignoredFields().isEmpty()) {
            headers.set(GotenbergOptions.IGNORED_HEADER, String.join(",", options.ignoredFields()));
        }
        outputs.stream().map(Output::report).filter(Objects::nonNull).findFirst().ifPresent(report -> {
            headers.set("X-Pdfa-Level", report.level().label());
            headers.set("X-Pdfa-Compliant", String.valueOf(outputs.stream().allMatch(o -> o.report().compliant())));
            String problems = reports.stream().flatMap(r -> r.violations().stream())
                    .map(ConversionController::problemCode).distinct().collect(java.util.stream.Collectors.joining(","));
            if (!problems.isEmpty()) {
                headers.set("X-Pdfa-Problems", problems.length() > 1000 ? problems.substring(0, 1000) : problems);
            }
        });
        if (outputs.size() == 1) {
            String name = (requestedName != null ? requestedName : outputs.getFirst().name()) + ".pdf";
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.attachment().filename(name).build());
            return ResponseEntity.ok().headers(headers).body(outputs.getFirst().pdf());
        }
        String name = (requestedName != null ? requestedName : "result") + ".zip";
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(name).build());
        return ResponseEntity.ok().headers(headers).body(zip(outputs));
    }

    /** "6.2.3.3-2: …" → "6.2.3.3-2", "TRANSPARENCY: …" → "TRANSPARENCY". */
    private static String problemCode(String problem) {
        int colon = problem.indexOf(':');
        return colon > 0 ? problem.substring(0, colon) : problem;
    }

    private static byte[] zip(List<Output> outputs) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            Set<String> used = new HashSet<>();
            for (Output output : outputs) {
                // rapport.docx och rapport.odt ger båda rapport.pdf.
                String name = output.name() + ".pdf";
                for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) {
                    name = output.name() + " (" + n + ").pdf";
                }
                zip.putNextEntry(new ZipEntry(name));
                zip.write(output.pdf());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Gotenbergs pappers- och marginalfält som en @page-regel. Tal utan enhet är tum, som i Gotenberg.
     * Utan fält bestämmer mallens egen @page (standard A4).
     */
    static Optional<String> pageCss(MultipartHttpServletRequest request, boolean preferCssPageSize) {
        String width = length(request, "paperWidth");
        String height = length(request, "paperHeight");
        boolean landscape = Boolean.TRUE.equals(bool(request, "landscape"));
        List<String> margins = Stream.of("marginTop", "marginRight", "marginBottom", "marginLeft")
                .map(field -> length(request, field)).toList();
        StringBuilder css = new StringBuilder();
        if (!preferCssPageSize && (width != null || height != null || landscape)) {
            String w = width != null ? width : "210mm";
            String h = height != null ? height : "297mm";
            css.append("size: ").append(landscape ? h + " " + w : w + " " + h).append("; ");
        }
        if (margins.stream().anyMatch(Objects::nonNull)) {
            css.append("margin: ").append(String.join(" ",
                    margins.stream().map(m -> m != null ? m : "0.39in").toList())).append("; ");
        }
        return css.isEmpty() ? Optional.empty() : Optional.of("@page { " + css + "}");
    }

    private static String length(MultipartHttpServletRequest request, String field) {
        String value = request.getParameter(field);
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim();
        if (!LENGTH.matcher(value).matches()) {
            throw ConversionException.badRequest("Ogiltigt värde för '" + field + "': " + value);
        }
        return Character.isDigit(value.charAt(value.length() - 1)) ? value + "in" : value;
    }

    private static Boolean bool(MultipartHttpServletRequest request, String field) {
        String value = request.getParameter(field);
        return value == null || value.isBlank() ? null : Boolean.parseBoolean(value.trim());
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

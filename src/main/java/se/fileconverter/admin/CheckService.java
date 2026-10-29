package se.fileconverter.admin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.springframework.stereotype.Service;
import se.fileconverter.api.ConversionException;
import se.fileconverter.api.Workspace;
import se.fileconverter.html.FontRegistry;
import se.fileconverter.html.HtmlConverter;
import se.fileconverter.office.OfficeConverter;
import se.fileconverter.pdfa.PdfaLevel;
import se.fileconverter.pdfa.PdfaService;

/**
 * Testbänken i administrationen: konverterar en fil precis som API:t gör och förklarar resultatet,
 * även när det inte blir giltig PDF/A.
 */
@Service
public class CheckService {

    private static final Set<String> GENERIC_FAMILIES = Set.of("serif", "sans-serif", "monospace", "cursive", "fantasy", "inherit", "initial");
    private static final Pattern CSS_FONT_FAMILY = Pattern.compile("font-family\\s*:\\s*([^;}\"<]+)", Pattern.CASE_INSENSITIVE);

    public record RequestedFont(String name, String status, String replacement) {
    }

    public record OutputFont(String name, String type, boolean embedded) {
    }

    /**
     * @param reason orsakskoden när filen avvisades, annars null
     * @param visual jämförelse med samma dokument utan PDF/A-rättningar, null om den inte gick att göra
     */
    public record Result(String route, String level, boolean converted, boolean compliant, String reason, String message,
            List<String> violations, List<RequestedFont> requestedFonts, List<OutputFont> outputFonts,
            int pages, long millis, VisualComparison.Result visual, String pdf) {
    }

    private final OfficeConverter office;
    private final HtmlConverter html;
    private final PdfaService pdfa;
    private final FontRegistry fonts;

    public CheckService(OfficeConverter office, HtmlConverter html, PdfaService pdfa, FontRegistry fonts) {
        this.office = office;
        this.html = html;
        this.pdfa = pdfa;
        this.fonts = fonts;
    }

    public Result check(Workspace workspace, PdfaLevel level) {
        long start = System.nanoTime();
        List<Path> files = workspace.files();
        Optional<Path> htmlFile = files.stream().filter(CheckService::isHtml).findFirst();
        String route;
        byte[] raw;
        Set<String> requested;
        // Samma dokument utan PDF/A, som facit för hur resultatet ska se ut.
        Supplier<byte[]> reference;
        try {
            if (htmlFile.isPresent()) {
                route = "html";
                Path index = workspace.dir().resolve("index.html");
                if (!htmlFile.get().equals(index)) {
                    Files.copy(htmlFile.get(), index, StandardCopyOption.REPLACE_EXISTING);
                }
                requested = cssFonts(Files.readString(index, StandardCharsets.UTF_8));
                raw = html.toPdf(workspace.dir(), Optional.empty(), Optional.of(level));
                reference = () -> html.toPdf(workspace.dir(), Optional.empty(), Optional.empty());
            } else if (files.size() == 1 && files.getFirst().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                route = "pdf";
                requested = Set.of();
                raw = Files.readAllBytes(files.getFirst());
                byte[] original = raw;
                reference = () -> original;
            } else {
                route = "office";
                requested = RequestedFonts.find(Files.readAllBytes(files.getFirst()));
                raw = office.toPdf(files.getFirst(), level);
                Path document = files.getFirst();
                reference = () -> office.toPdf(document, (PdfaLevel) null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<RequestedFont> requestedFonts = describe(requested);

        try {
            PdfaService.Result result = pdfa.convert(raw, level, false);
            String message = result.report().compliant()
                    ? "Filen klarar " + level.label() + "."
                    : result.report().level() != level
                    ? "Filen kunde inte bli " + level.label() + " och blev " + result.report().level().label() + "."
                    : "Filen konverterades men klarar inte " + level.label() + "-valideringen.";
            long millis = elapsed(start);
            VisualComparison.Result visual;
            try {
                visual = VisualComparison.compare(reference.get(), result.pdf());
            } catch (RuntimeException e) {
                visual = null;  // Jämförelsen är ett hjälpmedel och får inte fälla kontrollen.
            }
            return new Result(route, level.label(), true, result.report().compliant(), null, message,
                    result.report().violations(), requestedFonts, outputFonts(result.pdf()), pages(result.pdf()),
                    millis, visual, Base64.getEncoder().encodeToString(result.pdf()));
        } catch (ConversionException e) {
            // Visa det som gick att konvertera, så att man ser vad problemet gäller.
            return new Result(route, level.label(), false, false, e.reason().name(), e.getMessage(), e.details(), requestedFonts,
                    outputFonts(raw), pages(raw), elapsed(start), null, Base64.getEncoder().encodeToString(raw));
        }
    }

    private List<RequestedFont> describe(Set<String> requested) {
        Map<String, String> aliases = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        aliases.putAll(fonts.aliases());
        List<RequestedFont> result = new ArrayList<>();
        for (String name : requested) {
            if (fonts.hasFamily(name)) {
                result.add(new RequestedFont(name, "installerad", null));
            } else if (aliases.containsKey(name) && fonts.hasFamily(aliases.get(name))) {
                result.add(new RequestedFont(name, "ersätts", aliases.get(name)));
            } else {
                result.add(new RequestedFont(name, "saknas", null));
            }
        }
        return result;
    }

    private static Set<String> cssFonts(String html) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Matcher matcher = CSS_FONT_FAMILY.matcher(html);
        while (matcher.find()) {
            for (String part : matcher.group(1).split(",")) {
                String name = part.trim().replaceAll("^['\"]|['\"]$", "").replace("&quot;", "").trim();
                if (!name.isEmpty() && !GENERIC_FAMILIES.contains(name.toLowerCase(Locale.ROOT))) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static List<OutputFont> outputFonts(byte[] pdf) {
        Map<String, OutputFont> found = new LinkedHashMap<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PDPage page : doc.getPages()) {
                collectFonts(page.getResources(), visited, found);
            }
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(found.values());
    }

    private static void collectFonts(PDResources resources, Set<COSBase> visited, Map<String, OutputFont> found) throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) {
            return;
        }
        for (COSName name : resources.getFontNames()) {
            PDFont font = resources.getFont(name);
            if (font == null) {
                continue;
            }
            String fontName = font.getName() == null ? name.getName() : font.getName().replaceFirst("^[A-Z]{6}\\+", "");
            found.putIfAbsent(fontName, new OutputFont(fontName, font.getSubType(), font instanceof PDType3Font || font.isEmbedded()));
        }
        for (COSName name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDFormXObject form) {
                collectFonts(form.getResources(), visited, found);
            }
        }
    }

    private static int pages(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean isHtml(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm");
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}

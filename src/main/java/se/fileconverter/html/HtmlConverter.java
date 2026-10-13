package se.fileconverter.html;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.PageSizeUnits;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder.PdfAConformance;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import se.fileconverter.api.ConversionException;
import se.fileconverter.pdfa.PdfaLevel;

/**
 * Egna HTML-mallar till PDF med openhtmltopdf, som skapar taggad PDF/A-1a direkt.
 *
 * <p>Stöder CSS 2.1 plus delar av CSS3 (inte flexbox/grid, inte JavaScript). Mallen får bara läsa
 * filer som skickats med i anropet; nätverk och övriga filsystemet är spärrade.
 */
@Component
public class HtmlConverter {

    private static final byte[] SRGB = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();

    private final FontRegistry fonts;

    public HtmlConverter(FontRegistry fonts) {
        this.fonts = fonts;
    }

    /**
     * @param pageCss         @page-regler från formulärfälten, läggs sist så att de vinner över mallen
     * @param printBackground som Chromium: utan den skrivs bakgrundsfärger och bakgrundsbilder inte ut
     * @param outline         bokmärken i PDF:en från mallens rubriker (Gotenbergs generateDocumentOutline)
     */
    public record Options(Optional<String> pageCss, boolean printBackground, boolean outline) {
        public static Options defaults() {
            return new Options(Optional.empty(), false, false);
        }
    }

    public byte[] toPdf(Path workDir, Optional<String> pageCss, Optional<PdfaLevel> pdfa) {
        return toPdf(workDir, new Options(pageCss, false, false), pdfa);
    }

    /** @param workDir katalog med index.html, ev. header.html/footer.html, och resurser */
    public byte[] toPdf(Path workDir, Options options, Optional<PdfaLevel> pdfa) {
        Path index = workDir.resolve("index.html");
        if (!Files.isRegularFile(index)) {
            throw ConversionException.badRequest("Filen index.html saknas i anropet.");
        }
        try {
            Document html = Jsoup.parse(index.toFile(), "UTF-8");
            // Standardfont först så att mallens egna regler vinner. Utan den faller openhtmltopdf
            // tillbaka på inbyggda PDF-fonter som inte bäddas in, och då blir filen inte PDF/A.
            html.head().prepend("<style>html { font-family: sans-serif; }</style>");
            HeaderFooter.apply(html, workDir);
            if (!options.printBackground() && !mentionsPrintColorAdjust(html, workDir)) {
                // Chromium skriver inte ut bakgrunder utan printBackground (Gotenbergs standard), utom där
                // mallen själv kräver det med print-color-adjust.
                html.head().appendElement("style").appendText("*, *::before, *::after { background: none !important; }");
            }
            if (options.outline()) {
                Outline.apply(html);
            }
            options.pageCss().ifPresent(css -> html.head().appendElement("style").appendText(css));

            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useDefaultPageSize(210, 297, PageSizeUnits.MM);
            fonts.registerAll(builder);
            Path root = workDir.toRealPath();
            builder.useExternalResourceAccessControl((uri, type) -> isAllowed(uri, root),
                    ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            pdfa.ifPresent(level -> {
                builder.usePdfAConformance(level == PdfaLevel.PDFA_1A ? PdfAConformance.PDFA_1_A : PdfAConformance.PDFA_1_B);
                builder.useColorProfile(SRGB);
                if (level.requiresTagging()) {
                    builder.usePdfUaAccessibility(true);
                }
            });
            builder.withW3cDocument(new W3CDom().fromJsoup(html), root.toUri().toString());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("HTML-renderingen misslyckades", e);
        }
    }

    private static boolean mentionsPrintColorAdjust(Document html, Path workDir) throws IOException {
        if (html.html().contains("print-color-adjust")) {
            return true;
        }
        try (var files = Files.list(workDir)) {
            for (Path css : files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".css")).toList()) {
                if (Files.readString(css).contains("print-color-adjust")) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isAllowed(String uri, Path root) {
        if (uri == null) {
            return false;
        }
        if (uri.startsWith("data:")) {
            return true;
        }
        try {
            URI parsed = URI.create(uri);
            if (!"file".equals(parsed.getScheme())) {
                return false;
            }
            Path target = Paths.get(parsed).normalize();
            return target.startsWith(root);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

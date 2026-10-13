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
     * @param workDir   katalog med index.html och dess resurser
     * @param pageCss   @page-regler från formulärfälten, läggs sist så att de vinner över mallen
     */
    public byte[] toPdf(Path workDir, Optional<String> pageCss, Optional<PdfaLevel> pdfa) {
        Path index = workDir.resolve("index.html");
        if (!Files.isRegularFile(index)) {
            throw ConversionException.badRequest("Filen index.html saknas i anropet.");
        }
        try {
            Document html = Jsoup.parse(index.toFile(), "UTF-8");
            // Standardfont först så att mallens egna regler vinner. Utan den faller openhtmltopdf
            // tillbaka på inbyggda PDF-fonter som inte bäddas in, och då blir filen inte PDF/A.
            html.head().prepend("<style>html { font-family: sans-serif; }</style>");
            pageCss.ifPresent(css -> html.head().appendElement("style").appendText(css));

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

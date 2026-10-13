package se.fileconverter.html;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import se.fileconverter.config.ConverterProperties;

/**
 * De TrueType-fonter HTML-renderaren får använda, inlästa en gång vid start.
 *
 * <p>Bara TrueType: OpenType/CFF-fonter bäddas in på sätt som PDF/A-1 inte alltid godtar.
 * Alias låter mallar skriva t.ex. Arial och få den måttkompatibla Liberation Sans.
 */
@Component
public class FontRegistry {

    private static final Logger log = LoggerFactory.getLogger(FontRegistry.class);

    /** openhtmltopdf slår upp CSS:ens generiska familjer under dessa namn. */
    private static final Map<String, String> GENERIC_INTERNAL_NAMES = Map.of(
            "serif", "Serif",
            "sans-serif", "SansSerif",
            "monospace", "Monospaced");

    record FontFile(File file, String family, int weight, boolean italic) {
    }

    private final List<FontFile> fonts = new ArrayList<>();
    private final Map<String, String> aliases;

    public FontRegistry(ConverterProperties properties) {
        this.aliases = properties.fonts().aliases() == null ? Map.of() : properties.fonts().aliases();
        for (String dir : properties.fonts().dirs()) {
            scan(Paths.get(dir));
        }
        log.info("Registrerade {} TrueType-fonter för HTML-rendering", fonts.size());
    }

    public List<String> families() {
        return fonts.stream().map(FontFile::family).distinct().sorted().toList();
    }

    void registerAll(PdfRendererBuilder builder) {
        for (FontFile font : fonts) {
            register(builder, font, font.family());
        }
        aliases.forEach((alias, target) -> {
            for (FontFile font : fonts) {
                if (font.family().equalsIgnoreCase(target)) {
                    register(builder, font, alias);
                    String internal = GENERIC_INTERNAL_NAMES.get(alias.toLowerCase(Locale.ROOT));
                    if (internal != null) {
                        register(builder, font, internal);
                    }
                }
            }
        });
    }

    private static void register(PdfRendererBuilder builder, FontFile font, String family) {
        builder.useFont(font.file(), family, font.weight(), font.italic() ? FontStyle.ITALIC : FontStyle.NORMAL, true);
    }

    private void scan(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("Fontkatalogen {} finns inte", dir);
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttf"))
                    .sorted()
                    .forEach(this::read);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void read(Path path) {
        try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBufferedFile(path.toFile()))) {
            // Typografisk familj (namn-id 16) samlar t.ex. "Roboto Light" under "Roboto" med vikt 300.
            String family = ttf.getNaming() == null ? null
                    : Stream.of(ttf.getNaming().getName(16, 3, 1, 0x409), ttf.getNaming().getFontFamily())
                            .filter(name -> name != null && !name.isBlank())
                            .findFirst().orElse(null);
            if (family == null) {
                return;
            }
            OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
            int weight = os2 != null ? os2.getWeightClass() : 400;
            boolean italic = os2 != null && (os2.getFsSelection() & 1) != 0;
            fonts.add(new FontFile(path.toFile(), family, weight, italic));
        } catch (IOException e) {
            log.warn("Kunde inte läsa fonten {}: {}", path, e.getMessage());
        }
    }
}

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import se.fileconverter.assets.AssetStore;
import se.fileconverter.assets.FontInspector;
import se.fileconverter.config.ConverterProperties;

/**
 * De TrueType-fonter tjänsten får använda: imagens fonter plus de som laddats upp i administrationen.
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

    public enum Source { SYSTEM, UPLOADED }

    public record FontFile(File file, String family, String style, int weight, boolean italic, Source source) {
    }

    private record Snapshot(List<FontFile> fonts, Map<String, String> aliases) {
    }

    private final ConverterProperties.Fonts config;
    private final AssetStore store;
    private volatile Snapshot snapshot;

    public FontRegistry(ConverterProperties properties, AssetStore store) {
        this.config = properties.fonts();
        this.store = store;
        reload();
    }

    /** Läser om alla fontkataloger och alias. Anropas när administrerade resurser ändras. */
    public void reload() {
        List<FontFile> fonts = new ArrayList<>();
        for (String dir : config.dirs()) {
            scan(Paths.get(dir), Source.SYSTEM, fonts);
        }
        scan(store.fontsDir(), Source.UPLOADED, fonts);
        Map<String, String> aliases = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (config.aliases() != null) {
            aliases.putAll(config.aliases());
        }
        aliases.putAll(store.aliases());
        snapshot = new Snapshot(List.copyOf(fonts), new LinkedHashMap<>(aliases));
        log.info("{} TrueType-fonter och {} alias registrerade", fonts.size(), aliases.size());
    }

    public List<FontFile> fonts() {
        return snapshot.fonts();
    }

    /** Alias från konfigurationen med administrerade alias ovanpå. */
    public Map<String, String> aliases() {
        return snapshot.aliases();
    }

    public boolean hasFamily(String family) {
        return snapshot.fonts().stream().anyMatch(font -> font.family().equalsIgnoreCase(family));
    }

    void registerAll(PdfRendererBuilder builder) {
        Snapshot current = snapshot;
        for (FontFile font : current.fonts()) {
            register(builder, font, font.family());
        }
        current.aliases().forEach((alias, target) -> {
            for (FontFile font : current.fonts()) {
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

    private static void scan(Path dir, Source source, List<FontFile> into) {
        if (!Files.isDirectory(dir)) {
            log.debug("Fontkatalogen {} finns inte", dir);
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttf"))
                    .sorted()
                    .forEach(path -> read(path, source, into));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void read(Path path, Source source, List<FontFile> into) {
        try {
            FontInspector.FontInfo info = FontInspector.inspect(Files.readAllBytes(path));
            into.add(new FontFile(path.toFile(), info.family(), info.style(), info.weight(), info.italic(), source));
        } catch (FontInspector.InvalidFontException | IOException e) {
            log.warn("Hoppar över fonten {}: {}", path, e.getMessage());
        }
    }
}

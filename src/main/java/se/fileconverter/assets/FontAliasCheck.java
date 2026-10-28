package se.fileconverter.assets;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import se.fileconverter.html.FontRegistry;

/**
 * Fontalias vars målfont inte finns. Sådana alias hoppas över när mallar renderas, så en mall som
 * skriver {@code Arial} får då ingen font alls i stället för Liberation Sans. Det händer t.ex. om ett
 * fontpaket i imagen byter namn. Kontrolleras vid start och efter varje omladdning, loggas som
 * varning, visas i administrationen och räknas i {@code converter_font_aliases_missing}.
 */
@Component
public class FontAliasCheck {

    private static final Logger log = LoggerFactory.getLogger(FontAliasCheck.class);

    private final FontRegistry fonts;
    private volatile Map<String, String> missing = Map.of();

    public FontAliasCheck(FontRegistry fonts, MeterRegistry registry) {
        this.fonts = fonts;
        Gauge.builder("converter.font.aliases.missing", () -> missing.size())
                .description("Fontalias vars målfont inte är installerad").register(registry);
        check();
    }

    /** Kontrollerar aliasen mot fonterna som är registrerade nu. */
    public Map<String, String> check() {
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        fonts.aliases().forEach((alias, target) -> {
            if (!fonts.hasFamily(target)) {
                result.put(alias, target);
                log.warn("Fontaliaset {} -> {} saknar målfont och används inte", alias, target);
            }
        });
        missing = java.util.Collections.unmodifiableMap(result);
        return missing;
    }

    /** Alias utan målfont vid senaste kontrollen, alias mot målfont. */
    public Map<String, String> missing() {
        return missing;
    }
}

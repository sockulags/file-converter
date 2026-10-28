package se.fileconverter.html;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import se.fileconverter.api.ConversionException;

/**
 * Väljer motor för en HTML-mall. openhtmltopdf ger taggad PDF/A-1a direkt men klarar bara CSS 2.1
 * och lite CSS3. Mallar som är byggda för Gotenbergs Chromium (flexbox, grid, JavaScript m.m.)
 * renderas med Chromium i stället, så att de inte blir tyst felaktiga.
 *
 * <p>Ordning: fältet {@code converterEngine} om det finns, annars första signalen som kräver
 * Chromium, annars openhtmltopdf. Underlaget är index.html, header.html, footer.html och alla
 * .css-filer i anropet, utan CSS-kommentarer. Externa resurser avgör inte valet; de laddas ändå inte.
 */
public final class EngineSelector {

    public enum Engine {
        OPENHTMLTOPDF, CHROMIUM;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** @param reason kort orsak, en av en fast lista (säker som etikett i mätvärden) */
    public record Selection(Engine engine, String reason) {
    }

    /** Fält som bara har betydelse i en webbläsare. */
    private static final List<String> BROWSER_FIELDS = List.of("waitDelay", "waitForExpression", "waitForSelector",
            "emulatedMediaFeatures", "failOnConsoleExceptions");
    private static final Set<String> NON_SCRIPT_TYPES = Set.of("application/json", "application/ld+json", "text/template",
            "text/x-template", "text/html");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern FLEX = Pattern.compile("display\\s*:\\s*(inline-)?flex");
    private static final Pattern GRID = Pattern.compile("display\\s*:\\s*(inline-)?grid|grid-template");
    private static final Pattern CSS3 = Pattern.compile("position\\s*:\\s*sticky|var\\(\\s*--|@supports|@container"
            + "|(?<![-\\w])transform\\s*:|(?<![-\\w])filter\\s*:|box-shadow\\s*:|column-count\\s*:|(?<![-\\w])columns\\s*:"
            + "|(?<![-\\w])opacity\\s*:\\s*(0?\\.\\d|0\\s*[;}\"'])");

    private EngineSelector() {
    }

    /** @param field Gotenbergs formulärfält */
    public static Selection select(Path workDir, Function<String, String> field) {
        String forced = field.apply("converterEngine");
        if (forced != null && !forced.isBlank()) {
            return switch (forced.trim().toLowerCase(Locale.ROOT)) {
                case "chromium" -> new Selection(Engine.CHROMIUM, "vald");
                case "openhtmltopdf" -> new Selection(Engine.OPENHTMLTOPDF, "vald");
                default -> throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION,
                        "converterEngine ska vara chromium eller openhtmltopdf.");
            };
        }
        if (needsBrowserFields(field)) {
            return new Selection(Engine.CHROMIUM, "falt");
        }
        try {
            return signal(workDir).map(reason -> new Selection(Engine.CHROMIUM, reason))
                    .orElse(new Selection(Engine.OPENHTMLTOPDF, "standard"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean needsBrowserFields(Function<String, String> field) {
        if (BROWSER_FIELDS.stream().map(field).anyMatch(value -> value != null && !value.isBlank())) {
            return true;
        }
        String scale = field.apply("scale");
        String media = field.apply("emulatedMediaType");
        return isTrue(field.apply("singlePage")) || isTrue(field.apply("omitBackground"))
                || isTrue(field.apply("generateTaggedPdf"))
                || (scale != null && !scale.isBlank() && parse(scale) != 1.0)
                || (media != null && media.trim().equalsIgnoreCase("screen"));
    }

    private static Optional<String> signal(Path workDir) throws IOException {
        StringBuilder css = new StringBuilder();
        for (String name : List.of("index.html", "header.html", "footer.html")) {
            Path file = workDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            Document html = Jsoup.parse(file.toFile(), "UTF-8");
            if (hasScript(html)) {
                return Optional.of("script");
            }
            for (String element : List.of("svg", "canvas", "iframe")) {
                if (!html.select(element).isEmpty()) {
                    return Optional.of(element);
                }
            }
            if (!html.select("img[src$=.svg], object, embed").isEmpty()) {
                return Optional.of("svg");
            }
            html.select("style").forEach(style -> css.append(style.data()).append('\n'));
            html.select("[style]").forEach(element -> css.append(element.attr("style")).append(";\n"));
        }
        try (Stream<Path> files = Files.list(workDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".css")).toList()) {
                css.append(Files.readString(file)).append('\n');
            }
        }
        String text = COMMENTS.matcher(css).replaceAll(" ").toLowerCase(Locale.ROOT);
        if (FLEX.matcher(text).find()) {
            return Optional.of("flexbox");
        }
        if (GRID.matcher(text).find()) {
            return Optional.of("grid");
        }
        if (CSS3.matcher(text).find()) {
            return Optional.of("css3");
        }
        return Optional.empty();
    }

    private static boolean hasScript(Document html) {
        for (Element script : html.select("script")) {
            String type = script.attr("type").trim().toLowerCase(Locale.ROOT);
            if (!NON_SCRIPT_TYPES.contains(type)) {
                return true;
            }
        }
        for (Element element : html.getAllElements()) {
            for (Attribute attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase(Locale.ROOT);
                if ((name.startsWith("on") && name.length() > 2)
                        || attribute.getValue().trim().toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTrue(String value) {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private static double parse(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

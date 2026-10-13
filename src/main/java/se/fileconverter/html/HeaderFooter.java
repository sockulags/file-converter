package se.fileconverter.html;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Gotenbergs {@code header.html} och {@code footer.html} som löpande sidhuvud och sidfot.
 *
 * <p>Innehållet läggs in i mallen som CSS-löpande element ({@code position: running()}) i sidans
 * marginaler, så precis som i Gotenberg behöver mallen marginaler som rymmer dem. Klasserna
 * {@code pageNumber}, {@code totalPages}, {@code date} och {@code title} fylls i som i Chromium.
 * Stilar i huvudets och sidfotens {@code <head>} följer med och gäller hela dokumentet.
 */
final class HeaderFooter {

    private HeaderFooter() {
    }

    static void apply(Document html, Path workDir) {
        StringBuilder css = new StringBuilder();
        boolean any = false;
        any |= insert(html, workDir.resolve("header.html"), "fcHeader", "fc-header");
        any |= insert(html, workDir.resolve("footer.html"), "fcFooter", "fc-footer");
        if (!any) {
            return;
        }
        if (Files.isRegularFile(workDir.resolve("header.html"))) {
            css.append("@page { @top-center { content: element(fcHeader); width: 100%; vertical-align: bottom; } }\n");
        }
        if (Files.isRegularFile(workDir.resolve("footer.html"))) {
            css.append("@page { @bottom-center { content: element(fcFooter); width: 100%; vertical-align: top; } }\n");
        }
        css.append(".fc-header .pageNumber::after, .fc-footer .pageNumber::after { content: counter(page); }\n");
        css.append(".fc-header .totalPages::after, .fc-footer .totalPages::after { content: counter(pages); }\n");
        html.head().appendElement("style").appendText(css.toString());
    }

    private static boolean insert(Document html, Path file, String runningName, String cssClass) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        Document part;
        try {
            part = Jsoup.parse(file.toFile(), "UTF-8");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        part.select(".date").forEach(element -> element.text(LocalDate.now().toString()));
        part.select(".title").forEach(element -> element.text(html.title()));
        part.select(".url").forEach(element -> element.text(""));
        part.select(".pageNumber, .totalPages").forEach(Element::empty);
        part.head().select("style").forEach(style -> html.head().appendChild(style.clone()));

        Element running = html.body().prependElement("div")
                .addClass(cssClass)
                .attr("style", "position: running(" + runningName + ");");
        part.body().childNodes().forEach(node -> running.appendChild(node.clone()));
        return true;
    }
}

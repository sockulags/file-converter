package se.fileconverter.html;

import java.util.ArrayDeque;
import java.util.Deque;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Bokmärken (dokumentstruktur i PDF-läsarens sidopanel) från mallens rubriker h1–h6, som
 * Gotenbergs {@code generateDocumentOutline}. openhtmltopdf läser dem från {@code <bookmarks>} i head.
 */
final class Outline {

    private Outline() {
    }

    static void apply(Document html) {
        Element bookmarks = html.head().appendElement("bookmarks");
        Deque<Element> parents = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        int counter = 0;
        for (Element heading : html.body().select("h1, h2, h3, h4, h5, h6")) {
            if (heading.text().isBlank()) {
                continue;
            }
            if (heading.id().isEmpty()) {
                heading.id("fc-rubrik-" + (++counter));
            }
            int level = Integer.parseInt(heading.tagName().substring(1));
            while (!levels.isEmpty() && levels.peek() >= level) {
                levels.pop();
                parents.pop();
            }
            Element parent = parents.isEmpty() ? bookmarks : parents.peek();
            Element bookmark = parent.appendElement("bookmark").attr("name", heading.text()).attr("href", "#" + heading.id());
            parents.push(bookmark);
            levels.push(level);
        }
    }
}

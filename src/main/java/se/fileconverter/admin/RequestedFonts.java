package se.fileconverter.admin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fontnamn som ett Office-dokument hänvisar till, för att visa vad som saknas innan LibreOffice
 * tyst väljer en ersättare. Läser OOXML (docx/xlsx/pptx) och ODF (odt/ods/odp).
 */
final class RequestedFonts {

    private static final Pattern[] PATTERNS = {
            Pattern.compile("<w:font\\s+w:name=\"([^\"]+)\""),          // docx fontTable.xml
            Pattern.compile("<w:rFonts[^>]*?\\sw:(?:ascii|hAnsi)=\"([^\"]+)\""), // docx formatmallar och text
            Pattern.compile("<a:latin\\s+typeface=\"([^\"]+)\""),        // pptx/docx-teman
            Pattern.compile("<name\\s+val=\"([^\"]+)\""),                // xlsx styles.xml
            Pattern.compile("svg:font-family=\"'?([^\"']+)'?\""),        // ODF
            Pattern.compile("style:font-name=\"([^\"]+)\""),             // ODF
    };
    private static final long MAX_PART_BYTES = 20L * 1024 * 1024;

    private RequestedFonts() {
    }

    static Set<String> find(byte[] document) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(document))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                boolean relevant = name.endsWith("fontTable.xml") || name.contains("theme/") || name.endsWith("styles.xml")
                        || name.equals("word/document.xml")
                        || name.equals("content.xml") || (name.startsWith("ppt/slides/") && name.endsWith(".xml"));
                if (!relevant) {
                    continue;
                }
                String xml = new String(zip.readNBytes((int) MAX_PART_BYTES), StandardCharsets.UTF_8);
                for (Pattern pattern : PATTERNS) {
                    Matcher matcher = pattern.matcher(xml);
                    while (matcher.find()) {
                        String font = matcher.group(1).trim();
                        // "+mj-lt" m.fl. är platshållare för temats fonter, inte fontnamn.
                        if (!font.isEmpty() && !font.startsWith("+")) {
                            names.add(font);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Inte ett zip-baserat format (t.ex. .doc eller .rtf): ingen lista, men konverteringen kan fungera.
        }
        return names;
    }
}

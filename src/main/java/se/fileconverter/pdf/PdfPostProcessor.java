package se.fileconverter.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.springframework.stereotype.Component;
import se.fileconverter.api.ConversionException;

/**
 * Gotenbergs fält som gäller färdiga PDF:er oavsett route: {@code metadata}, {@code flatten} och
 * sidurval. Körs före PDF/A-normaliseringen, som sedan skriver XMP-metadata utifrån samma värden.
 */
@Component
public class PdfPostProcessor {

    public byte[] apply(byte[] pdf, Map<String, Object> metadata, boolean flatten) {
        if (metadata.isEmpty() && !flatten) {
            return pdf;
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (!metadata.isEmpty()) {
                writeMetadata(doc.getDocumentInformation(), metadata);
            }
            if (flatten) {
                PDAcroForm form = doc.getDocumentCatalog().getAcroForm(null);
                if (form != null) {
                    form.flatten();
                }
            }
            return save(doc);
        } catch (IOException e) {
            throw ConversionException.unprocessable(ConversionException.Reason.UNREADABLE_PDF,
                    "PDF-filen kunde inte efterbehandlas.", List.of(String.valueOf(e.getMessage())));
        }
    }

    /** Behåller sidorna i t.ex. "1-3, 7" (1-baserat). Sidor utanför dokumentet ignoreras. */
    public byte[] selectPages(byte[] pdf, String ranges) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            TreeSet<Integer> keep = new TreeSet<>();
            for (String part : ranges.split(",")) {
                String[] bounds = part.split("-");
                int from = Integer.parseInt(bounds[0].trim());
                int to = bounds.length > 1 ? Integer.parseInt(bounds[1].trim()) : from;
                for (int page = from; page <= to && page <= doc.getNumberOfPages(); page++) {
                    keep.add(page);
                }
            }
            if (keep.isEmpty()) {
                throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION,
                        "nativePageRanges valde inga sidor (dokumentet har " + doc.getNumberOfPages() + ").");
            }
            for (int page = doc.getNumberOfPages(); page >= 1; page--) {
                if (!keep.contains(page)) {
                    doc.removePage(page - 1);
                }
            }
            return save(doc);
        } catch (IOException e) {
            throw ConversionException.unprocessable(ConversionException.Reason.UNREADABLE_PDF,
                    "Sidurvalet kunde inte göras.", List.of(String.valueOf(e.getMessage())));
        }
    }

    private static void writeMetadata(PDDocumentInformation info, Map<String, Object> metadata) {
        metadata.forEach((key, value) -> {
            switch (key) {
                case "Title" -> info.setTitle(text(value));
                case "Author" -> info.setAuthor(text(value));
                case "Subject" -> info.setSubject(text(value));
                case "Keywords" -> info.setKeywords(text(value));
                case "Creator" -> info.setCreator(text(value));
                case "Producer" -> info.setProducer(text(value));
                case "CreationDate" -> info.setCreationDate(date(value));
                case "ModDate" -> info.setModificationDate(date(value));
                default -> {
                    // Trapped, Marked, PDFVersion m.fl. styrs av PDF/A-normaliseringen.
                }
            }
        });
    }

    private static String text(Object value) {
        if (value instanceof Collection<?> list) {
            List<String> parts = new ArrayList<>();
            list.forEach(item -> parts.add(String.valueOf(item)));
            return String.join(", ", parts);
        }
        return value == null ? null : String.valueOf(value);
    }

    private static GregorianCalendar date(Object value) {
        return value == null ? null : GregorianCalendar.from(OffsetDateTime.parse(String.valueOf(value)).toZonedDateTime());
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}

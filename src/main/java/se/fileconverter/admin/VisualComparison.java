package se.fileconverter.admin;

import java.awt.image.BufferedImage;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Jämför hur två PDF:er ser ut, sida för sida. PDF/A-godkänd betyder inte att filen ser rätt ut,
 * så testbänken jämför resultatet med samma dokument utan PDF/A-rättningar.
 */
public final class VisualComparison {

    /** Låg upplösning räcker för att se fel bilder, fonter och layout, och håller nere tid och minne. */
    private static final float DPI = 50f;
    private static final int MAX_PAGES = 30;
    /** Skillnad per färgkanal som räknas som avvikelse; kantutjämning ger mindre skillnader än så. */
    private static final int TOLERANCE = 40;

    /**
     * @param differingPercent största andelen avvikande pixlar på någon sida
     * @param worstPage        sidan med störst avvikelse (1-baserad)
     * @param pagesCompared    antal jämförda sidor
     * @param pageCountDiffers originalet och resultatet har olika antal sidor
     */
    public record Result(double differingPercent, int worstPage, int pagesCompared, boolean pageCountDiffers) {
    }

    private VisualComparison() {
    }

    static Result compare(byte[] reference, byte[] candidate) {
        try (PDDocument a = Loader.loadPDF(reference); PDDocument b = Loader.loadPDF(candidate)) {
            int pages = Math.min(Math.min(a.getNumberOfPages(), b.getNumberOfPages()), MAX_PAGES);
            PDFRenderer renderA = new PDFRenderer(a);
            PDFRenderer renderB = new PDFRenderer(b);
            // Stora bilder packas inte upp i full upplösning när sidan ändå renderas med 50 dpi.
            renderA.setSubsamplingAllowed(true);
            renderB.setSubsamplingAllowed(true);
            double worst = 0;
            int worstPage = 0;
            for (int i = 0; i < pages; i++) {
                double percent = differingPercent(renderA.renderImageWithDPI(i, DPI, ImageType.RGB),
                        renderB.renderImageWithDPI(i, DPI, ImageType.RGB));
                if (percent > worst || worstPage == 0) {
                    worst = percent;
                    worstPage = i + 1;
                }
            }
            return new Result(worst, worstPage, pages, a.getNumberOfPages() != b.getNumberOfPages());
        } catch (IOException e) {
            return null;
        }
    }

    private static double differingPercent(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return 100.0;
        }
        long differing = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    if (Math.abs(((pa >> shift) & 0xff) - ((pb >> shift) & 0xff)) > TOLERANCE) {
                        differing++;
                        break;
                    }
                }
            }
        }
        return 100.0 * differing / ((long) a.getWidth() * a.getHeight());
    }
}

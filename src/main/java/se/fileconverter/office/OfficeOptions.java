package se.fileconverter.office;

import java.util.Map;
import se.fileconverter.pdfa.PdfaLevel;

/**
 * Inställningar för en LibreOffice-konvertering.
 *
 * @param pdfa       nivån som begärts, eller null utan PDF/A. LibreOffice exporterar taggad PDF/A som
 *                   underlag för normaliseringen
 * @param filterData LibreOffice-exportinställningar (Gotenbergs fält, t.ex. PageRange, ExportBookmarks)
 * @param landscape  gör alla sidformat liggande
 * @param password   lösenord för att öppna ett skyddat dokument
 */
public record OfficeOptions(PdfaLevel pdfa, Map<String, Object> filterData, boolean landscape, String password) {

    public static OfficeOptions defaults(PdfaLevel pdfa) {
        return new OfficeOptions(pdfa, Map.of(), false, null);
    }

    public boolean pdfA() {
        return pdfa != null;
    }
}

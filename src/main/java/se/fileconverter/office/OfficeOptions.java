package se.fileconverter.office;

import java.util.Map;

/**
 * Inställningar för en LibreOffice-konvertering.
 *
 * @param pdfA       exportera som taggad PDF/A, underlag för PDF/A-1-normaliseringen
 * @param filterData LibreOffice-exportinställningar (Gotenbergs fält, t.ex. PageRange, ExportBookmarks)
 * @param landscape  gör alla sidformat liggande
 * @param password   lösenord för att öppna ett skyddat dokument
 */
public record OfficeOptions(boolean pdfA, Map<String, Object> filterData, boolean landscape, String password) {

    public static OfficeOptions defaults(boolean pdfA) {
        return new OfficeOptions(pdfA, Map.of(), false, null);
    }
}

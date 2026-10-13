package se.fileconverter.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.stereotype.Component;
import se.fileconverter.api.ConversionException;

/**
 * Slår ihop PDF:er i given ordning. PDFBox slår även ihop taggstrukturerna när alla delar är taggade.
 */
@Component
public class PdfMerger {

    public byte[] merge(List<byte[]> pdfs) {
        if (pdfs.size() < 2) {
            throw ConversionException.badRequest("Merge kräver minst två PDF-filer.");
        }
        PDFMergerUtility merger = new PDFMergerUtility();
        pdfs.forEach(pdf -> merger.addSource(new RandomAccessReadBuffer(pdf)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        merger.setDestinationStream(out);
        try {
            merger.mergeDocuments(IOUtils.createMemoryOnlyStreamCache());
        } catch (IOException e) {
            throw ConversionException.unprocessable("PDF-filerna kunde inte slås ihop.", List.of(String.valueOf(e.getMessage())));
        }
        return out.toByteArray();
    }
}

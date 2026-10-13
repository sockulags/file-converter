package se.fileconverter.pdfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import se.fileconverter.TestPdfs;

class TransparentImageFlattenerTest {

    private final PdfaNormalizer normalizer = new PdfaNormalizer();
    private final PdfaValidator validator = new PdfaValidator();

    @Test
    void transparentCornersTakeTheColourOfThePageBehind() throws IOException {
        byte[] input = TestPdfs.alphaImageOnColor(false);
        byte[] output = normalizer.normalize(input, PdfaLevel.PDFA_1B);

        assertThat(validator.validate(output, PdfaLevel.PDFA_1B).violations()).isEmpty();
        assertThat(softMaskImages(output)).isZero();
        // Hörnet utanför ellipsen ska ha sidans bakgrundsfärg, inte vitt.
        pixel(output, 0, 105, 495).isCloseTo(TestPdfs.BACKGROUND);
        // Mitten ska se ut som ellipsen blandad med bakgrunden, precis som originalet.
        pixel(output, 0, 200, 450).isCloseTo(pixel(input, 0, 200, 450));
    }

    @Test
    void imageDirectlyOnPageGetsWhiteNotBlack() throws IOException {
        byte[] input = TestPdfs.alphaImageOnBlankPage();
        byte[] output = normalizer.normalize(input, PdfaLevel.PDFA_1B);

        assertThat(softMaskImages(output)).isZero();
        pixel(output, 0, 105, 495).isCloseTo(Color.WHITE);
        pixel(output, 0, 200, 450).isCloseTo(pixel(input, 0, 200, 450));
    }

    @Test
    void rotatedImageIsComposedAlongItsOwnAxes() throws IOException {
        byte[] input = TestPdfs.alphaImageOnColor(true);
        byte[] output = normalizer.normalize(input, PdfaLevel.PDFA_1B);

        assertThat(validator.validate(output, PdfaLevel.PDFA_1B).violations()).isEmpty();
        assertThat(softMaskImages(output)).isZero();
        // Bildens övre vänstra hörn efter 30° rotation runt (100, 400).
        pixel(output, 0, 57, 487).isCloseTo(TestPdfs.BACKGROUND);
        pixel(output, 0, 150, 470).isCloseTo(pixel(input, 0, 150, 470));
    }

    @Test
    void imageOnDifferentBackgroundsFallsBackToWhite() throws IOException {
        byte[] output = normalizer.normalize(TestPdfs.sameAlphaImageOnTwoBackgrounds(), PdfaLevel.PDFA_1B);

        assertThat(validator.validate(output, PdfaLevel.PDFA_1B).violations()).isEmpty();
        pixel(output, 1, 105, 495).isCloseTo(Color.WHITE);
    }

    private static int softMaskImages(byte[] pdf) throws IOException {
        int count = 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(name) instanceof PDImageXObject image
                            && image.getCOSObject().containsKey(COSName.SMASK)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Färgen i PDF-punkten (x, y) vid 72 dpi. */
    private static ColorAssert pixel(byte[] pdf, int page, int x, int y) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage image = new PDFRenderer(doc).renderImage(page, 1f);
            int height = image.getHeight();
            return new ColorAssert(new Color(image.getRGB(x, height - y)));
        }
    }

    private record ColorAssert(Color actual) {
        void isCloseTo(Color expected) {
            assertThat(Math.abs(actual.getRed() - expected.getRed())).as("röd %s mot %s", actual, expected).isLessThanOrEqualTo(8);
            assertThat(Math.abs(actual.getGreen() - expected.getGreen())).as("grön %s mot %s", actual, expected).isLessThanOrEqualTo(8);
            assertThat(Math.abs(actual.getBlue() - expected.getBlue())).as("blå %s mot %s", actual, expected).isLessThanOrEqualTo(8);
        }

        void isCloseTo(ColorAssert other) {
            isCloseTo(other.actual);
        }
    }
}

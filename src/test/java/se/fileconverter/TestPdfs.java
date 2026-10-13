package se.fileconverter;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;

/**
 * Syntetiska PDF:er med de egenskaper som brukar stoppa PDF/A-1, som efterbildning av kundfiler.
 */
public final class TestPdfs {

    public static final File FONT = List.of(
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf")
            .stream().map(File::new).filter(File::isFile).findFirst()
            .orElseThrow(() -> new IllegalStateException("Testerna kräver DejaVu eller Liberation"));

    private TestPdfs() {
    }

    /** Oinbäddad standardfont, bild med alfakanal, länk utan utskriftsflagga, ingen metadata. */
    public static byte[] untaggedWithAlphaImage() {
        return build(doc -> {
            PDPage page = doc.getPage(0);
            BufferedImage argb = new BufferedImage(120, 60, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            g.setColor(new Color(30, 90, 200, 160));
            g.fillOval(0, 0, 120, 60);
            g.dispose();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, PDType0Font.load(doc, FONT), "Kundens dokument – åäö");
                cs.drawImage(LosslessFactory.createFromImage(doc, argb), 72, 600);
            }
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 700, 100, 20));
            page.getAnnotations().add(link);
        });
    }

    /** Markerad som taggad men med tomt strukturträd, som LibreOffice ibland skapar. */
    public static byte[] withEmptyStructureTree() {
        return build(doc -> {
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                text(cs, PDType0Font.load(doc, FONT), "Taggad på pappret");
            }
            PDMarkInfo markInfo = new PDMarkInfo();
            markInfo.setMarked(true);
            doc.getDocumentCatalog().setMarkInfo(markInfo);
            doc.getDocumentCatalog().setStructureTreeRoot(new PDStructureTreeRoot());
        });
    }

    public static byte[] withTransparency() {
        return build(doc -> {
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.5f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                cs.setGraphicsStateParameters(gs);
                cs.addRect(72, 600, 100, 100);
                cs.fill();
            }
        });
    }

    public static byte[] withStandardFont() {
        return build(doc -> {
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                text(cs, new PDType1Font(Standard14Fonts.FontName.HELVETICA), "Helvetica");
            }
        });
    }

    public static byte[] encrypted() {
        return build(doc -> doc.protect(new StandardProtectionPolicy("ägare", "", new AccessPermission())));
    }

    public static byte[] fixture(String name) {
        try (InputStream in = TestPdfs.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Saknar fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void text(PDPageContentStream cs, PDFont font, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, 12);
        cs.newLineAtOffset(72, 750);
        cs.showText(text);
        cs.endText();
    }

    private interface DocWriter {
        void write(PDDocument doc) throws IOException;
    }

    private static byte[] build(DocWriter writer) {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            writer.write(doc);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

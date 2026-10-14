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
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.util.Matrix;

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

    public static final Color BACKGROUND = new Color(200, 230, 180);
    public static final Color BACKGROUND_2 = new Color(170, 190, 240);
    public static final Color ELLIPSE = new Color(30, 90, 200, 160);

    /**
     * En halvgenomskinlig ellips (200 x 100 pt) med genomskinliga hörn, placerad på en färgad sida.
     * Ellipsens nedre vänstra hörn ligger på (100, 400), eventuellt roterad 30° runt den punkten.
     */
    public static byte[] alphaImageOnColor(boolean rotated) {
        return build(doc -> {
            PDImageXObject image = LosslessFactory.createFromImage(doc, ellipse());
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                fillPage(cs, BACKGROUND);
                Matrix placement = Matrix.getTranslateInstance(100, 400);
                if (rotated) {
                    placement.concatenate(Matrix.getRotateInstance(Math.toRadians(30), 0, 0));
                }
                placement.concatenate(Matrix.getScaleInstance(200, 100));
                cs.drawImage(image, placement);
            }
        });
    }

    /** Bilden direkt på en vit sida, utan något ritat bakom. Det vanligaste fallet: en logga. */
    public static byte[] alphaImageOnBlankPage() {
        return build(doc -> {
            PDImageXObject image = LosslessFactory.createFromImage(doc, ellipse());
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                cs.drawImage(image, 100, 400, 200, 100);
            }
        });
    }

    /** Samma bildobjekt på två sidor med olika bakgrundsfärg. */
    public static byte[] sameAlphaImageOnTwoBackgrounds() {
        return build(doc -> {
            PDImageXObject image = LosslessFactory.createFromImage(doc, ellipse());
            doc.addPage(new PDPage(PDRectangle.A4));
            Color[] colors = {BACKGROUND, BACKGROUND_2};
            for (int i = 0; i < 2; i++) {
                try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(i))) {
                    fillPage(cs, colors[i]);
                    cs.drawImage(image, 100, 400, 200, 100);
                }
            }
        });
    }

    private static BufferedImage ellipse() {
        BufferedImage argb = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.setColor(ELLIPSE);
        g.fillOval(0, 0, 400, 200);
        g.dispose();
        return argb;
    }

    private static void fillPage(PDPageContentStream cs, Color color) throws IOException {
        cs.setNonStrokingColor(color);
        cs.addRect(0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
        cs.fill();
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

    /**
     * En 7000 x 7000 pixlar stor 16-bitarsbild (98 MB uppackad) som bara är några hundra kB komprimerad.
     * Typexemplet på en fil som skulle ta slut på minnet om bilden packades upp.
     */
    public static byte[] withHugeImage() {
        return build(doc -> {
            int size = 7000;
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (java.util.zip.DeflaterOutputStream out = new java.util.zip.DeflaterOutputStream(compressed)) {
                byte[] row = new byte[size * 2];
                for (int y = 0; y < size; y++) {
                    out.write(row);
                }
            }
            PDImageXObject image = new PDImageXObject(doc, new java.io.ByteArrayInputStream(compressed.toByteArray()),
                    org.apache.pdfbox.cos.COSName.FLATE_DECODE, size, size, 16,
                    org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray.INSTANCE);
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                cs.drawImage(image, 72, 72, 400, 400);
            }
        });
    }

    /**
     * CMYK-innehåll (fyllning med DeviceCMYK och en CMYK-bild), som i tryckfärdiga loggor.
     * Med sRGB som utdataprofil bryter det mot PDF/A-1 om ingen DefaultCMYK anges.
     */
    public static byte[] withCmyk() {
        return build(doc -> {
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                cs.setNonStrokingColor(0.1f, 0.7f, 0f, 0f);
                cs.addRect(72, 600, 200, 100);
                cs.fill();
                byte[] pixels = new byte[40 * 20 * 4];
                for (int i = 0; i < pixels.length; i += 4) {
                    pixels[i] = (byte) 255;
                }
                PDImageXObject image = new PDImageXObject(doc, new java.io.ByteArrayInputStream(pixels),
                        org.apache.pdfbox.cos.COSName.FLATE_DECODE, 40, 20, 8,
                        org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK.INSTANCE);
                cs.drawImage(image, 72, 400, 80, 40);
            }
        });
    }

    /** En bild med ICC-profil i version 4, som PDF/A-1 inte godtar. */
    public static byte[] withIccV4Image() {
        return build(doc -> {
            byte[] profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData().clone();
            profile[8] = 4;
            org.apache.pdfbox.pdmodel.common.PDStream stream = new org.apache.pdfbox.pdmodel.common.PDStream(doc,
                    new java.io.ByteArrayInputStream(profile), org.apache.pdfbox.cos.COSName.FLATE_DECODE);
            stream.getCOSObject().setInt(org.apache.pdfbox.cos.COSName.N, 3);
            org.apache.pdfbox.cos.COSArray iccBased = new org.apache.pdfbox.cos.COSArray();
            iccBased.add(org.apache.pdfbox.cos.COSName.ICCBASED);
            iccBased.add(stream);
            PDImageXObject image = new PDImageXObject(doc, new java.io.ByteArrayInputStream(new byte[30 * 10 * 3]),
                    org.apache.pdfbox.cos.COSName.FLATE_DECODE, 30, 10, 8,
                    org.apache.pdfbox.pdmodel.graphics.color.PDICCBased.create(iccBased, null));
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                cs.drawImage(image, 72, 400, 90, 30);
            }
        });
    }

    /** Ett ifyllt formulärfält, för att testa plattning (Gotenbergs flatten). */
    public static byte[] withFormField() {
        return build(doc -> {
            org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDType0Font font = PDType0Font.load(doc, new java.io.FileInputStream(FONT), false);
            org.apache.pdfbox.pdmodel.PDResources resources = new org.apache.pdfbox.pdmodel.PDResources();
            org.apache.pdfbox.cos.COSName fontName = resources.add(font);
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/" + fontName.getName() + " 12 Tf 0 g");
            org.apache.pdfbox.pdmodel.interactive.form.PDTextField field = new org.apache.pdfbox.pdmodel.interactive.form.PDTextField(form);
            field.setPartialName("namn");
            form.getFields().add(field);
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(72, 700, 200, 20));
            widget.setPage(doc.getPage(0));
            widget.setPrinted(true);
            doc.getPage(0).getAnnotations().add(widget);
            field.setValue("Anna Andersson");
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                text(cs, font, "Ansökan");
            }
        });
    }

    /** En synlig länk och en dold textanteckning (t.ex. en granskningskommentar). */
    public static byte[] withHiddenAnnotation() {
        return build(doc -> {
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
                text(cs, PDType0Font.load(doc, FONT), "Dokument med dold kommentar");
            }
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 700, 100, 20));
            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(72, 600, 20, 20));
            note.setContents("Intern kommentar");
            note.setHidden(true);
            doc.getPage(0).getAnnotations().add(link);
            doc.getPage(0).getAnnotations().add(note);
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

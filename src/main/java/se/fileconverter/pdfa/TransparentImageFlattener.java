package se.fileconverter.pdfa;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gör bilder med alfakanal (SMask) ogenomskinliga mot den bakgrund de faktiskt ligger på.
 *
 * <p>Sidan renderas, och precis när en sådan bild ska ritas tas ett utsnitt av det som redan finns
 * under den (bakgrundsfärg, toningar, andra bilder, text). Bilden läggs ovanpå utsnittet och
 * ersätter originalet. Resultatet ser likadant ut men innehåller ingen transparens.
 *
 * <p>Används samma bild på flera ställen med olika bakgrund går det inte att ersätta den med en
 * enda bild. Den lämnas då åt {@link PdfaNormalizer}, som lägger den mot vit bakgrund.
 */
final class TransparentImageFlattener {

    private static final Logger log = LoggerFactory.getLogger(TransparentImageFlattener.class);

    /** Upplösning för bakgrundsrenderingen, med tak för mycket stora sidor. */
    private static final float DPI = 200f;
    private static final long MAX_CANVAS_PIXELS = 40_000_000L;

    private TransparentImageFlattener() {
    }

    /** @return antal bilder som ersattes */
    static int flatten(PDDocument doc) throws IOException {
        Map<COSStream, List<BufferedImage>> composites = new IdentityHashMap<>();
        Capture capture = new Capture(composites);
        PDFRenderer renderer = new PDFRenderer(doc) {
            @Override
            protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
                return new CapturingDrawer(parameters, capture);
            }
        };
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage page = doc.getPage(i);
            if (!hasSoftMaskImage(page.getResources(), Collections.newSetFromMap(new IdentityHashMap<>()))) {
                continue;
            }
            renderPage(renderer, page, i, capture);
        }

        int replaced = 0;
        for (Map.Entry<COSStream, List<BufferedImage>> entry : composites.entrySet()) {
            List<BufferedImage> versions = entry.getValue();
            if (versions.stream().allMatch(version -> samePixels(version, versions.getFirst()))) {
                replaceInPlace(doc, entry.getKey(), versions.getFirst());
                replaced++;
            } else {
                log.info("Bild används mot {} olika bakgrunder och läggs mot vitt", versions.size());
            }
        }
        return replaced;
    }

    private static void renderPage(PDFRenderer renderer, PDPage page, int index, Capture capture) throws IOException {
        PDRectangle crop = page.getCropBox();
        boolean rotated = page.getRotation() % 180 != 0;
        float scale = DPI / 72f;
        double pixels = (double) crop.getWidth() * crop.getHeight() * scale * scale;
        if (pixels > MAX_CANVAS_PIXELS) {
            scale *= (float) Math.sqrt(MAX_CANVAS_PIXELS / pixels);
        }
        int width = Math.max(1, Math.round((rotated ? crop.getHeight() : crop.getWidth()) * scale));
        int height = Math.max(1, Math.round((rotated ? crop.getWidth() : crop.getHeight()) * scale));

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            capture.canvas = canvas;
            capture.root = graphics;
            renderer.renderPageToGraphics(index, graphics, scale);
        } finally {
            graphics.dispose();
            capture.canvas = null;
            capture.root = null;
        }
    }

    private static final class Capture {
        final Map<COSStream, List<BufferedImage>> composites;
        BufferedImage canvas;
        Graphics2D root;

        Capture(Map<COSStream, List<BufferedImage>> composites) {
            this.composites = composites;
        }
    }

    private static final class CapturingDrawer extends PageDrawer {
        private final Capture capture;

        CapturingDrawer(PageDrawerParameters parameters, Capture capture) throws IOException {
            super(parameters);
            this.capture = capture;
        }

        @Override
        public void drawImage(PDImage image) throws IOException {
            // Inuti transparensgrupper ritar PDFBox på en egen yta, där bakgrunden inte är känd.
            if (image instanceof PDImageXObject xobject
                    && xobject.getCOSObject().containsKey(COSName.SMASK)
                    && getGraphics() == capture.root) {
                BufferedImage composite = compose(xobject);
                if (composite != null) {
                    capture.composites.computeIfAbsent(xobject.getCOSObject(), k -> new ArrayList<>()).add(composite);
                }
            }
            super.drawImage(image);
        }

        private BufferedImage compose(PDImageXObject image) throws IOException {
            BufferedImage foreground = image.getImage();
            int w = foreground.getWidth();
            int h = foreground.getHeight();
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            AffineTransform deviceFromPixel = new AffineTransform(getGraphics().getTransform());
            deviceFromPixel.concatenate(ctm.createAffineTransform());
            // Bildpixel (x, y) ligger på (x/w, 1 - y/h) i bildens enhetskvadrat.
            deviceFromPixel.concatenate(new AffineTransform(1.0 / w, 0, 0, -1.0 / h, 0, 1));

            BufferedImage composite = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = composite.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setTransform(deviceFromPixel.createInverse());
                g.drawImage(capture.canvas, 0, 0, null);
                g.setTransform(new AffineTransform());
                g.drawImage(foreground, 0, 0, null);
            } catch (NoninvertibleTransformException e) {
                return null;
            } finally {
                g.dispose();
            }
            return composite;
        }
    }

    private static boolean hasSoftMaskImage(PDResources resources, Set<COSBase> visited) throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDImageXObject image && image.getCOSObject().containsKey(COSName.SMASK)) {
                return true;
            }
            if (xobject instanceof PDFormXObject form && hasSoftMaskImage(form.getResources(), visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        return a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight()
                && Arrays.equals(((DataBufferInt) a.getRaster().getDataBuffer()).getData(),
                        ((DataBufferInt) b.getRaster().getDataBuffer()).getData());
    }

    /**
     * Skriver över bildens ström i stället för att lägga till ett nytt objekt, så att alla
     * referenser till bilden (sidor, formulär, mönster) pekar på den nya versionen utan omskrivning.
     */
    private static void replaceInPlace(PDDocument doc, COSStream target, BufferedImage composite) throws IOException {
        boolean photo = COSName.DCT_DECODE.equals(target.getDictionaryObject(COSName.FILTER));
        PDImageXObject replacement = photo
                ? JPEGFactory.createFromImage(doc, composite, 0.92f)
                : LosslessFactory.createFromImage(doc, composite);
        COSStream source = replacement.getCOSObject();
        byte[] raw;
        try (InputStream in = source.createRawInputStream()) {
            raw = in.readAllBytes();
        }
        for (COSName key : new ArrayList<>(target.keySet())) {
            target.removeItem(key);
        }
        for (Map.Entry<COSName, COSBase> item : source.entrySet()) {
            if (!COSName.LENGTH.equals(item.getKey())) {
                target.setItem(item.getKey(), item.getValue());
            }
        }
        try (OutputStream out = target.createRawOutputStream()) {
            out.write(raw);
        }
    }
}

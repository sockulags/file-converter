package se.fileconverter.pdfa;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.AdobePDFSchema;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.xml.XmpSerializer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import se.fileconverter.api.ConversionException;

/**
 * Gör en befintlig PDF till PDF/A-1 utan att rendera om innehållet.
 *
 * <p>Rättar det som går att rätta mekaniskt (metadata, färgprofil, CIDSet, bilder med alfakanal,
 * otillåtna actions, xref-strömmar) och avvisar det som inte går att rätta utan att ändra hur
 * dokumentet ser ut: kryptering, signaturer, saknade fonter, verklig transparens och, för 1a,
 * avsaknad av taggstruktur. Resultatet ska alltid valideras efteråt.
 */
@Component
public class PdfaNormalizer {

    private static final COSName GTS_PDFA1 = COSName.getPDFName("GTS_PDFA1");
    private static final Set<String> FORBIDDEN_ANNOTATIONS = Set.of("FileAttachment", "Sound", "Movie", "3D", "Screen", "RichMedia");
    private static final Set<String> FORBIDDEN_ACTIONS = Set.of("Launch", "Sound", "Movie", "ResetForm", "ImportData", "JavaScript");
    private static final int ANNOT_INVISIBLE = 1, ANNOT_HIDDEN = 2, ANNOT_PRINT = 4, ANNOT_NO_VIEW = 32, ANNOT_TOGGLE_NO_VIEW = 256;

    public byte[] normalize(byte[] pdf, PdfaLevel level) {
        try (PDDocument doc = load(pdf)) {
            rejectUnsupported(doc, level);
            Pass pass = new Pass(doc);
            pass.run();
            pass.rejectIfProblems();

            removeForbiddenCatalogEntries(doc);
            ensureOutputIntent(doc);
            ensureDocumentId(doc);
            writeMetadata(doc, level);

            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length);
            // PDF/A-1 bygger på PDF 1.4: inga xref- eller objektströmmar.
            doc.save(out, CompressParameters.NO_COMPRESSION);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "PDF-filen kunde inte bearbetas: " + e.getMessage(), List.of(), e);
        }
    }

    private static PDDocument load(byte[] pdf) throws IOException {
        try {
            return Loader.loadPDF(pdf);
        } catch (InvalidPasswordException e) {
            throw ConversionException.unprocessable("PDF-filen är lösenordsskyddad.",
                    List.of("PDF/A tillåter inte kryptering. Skicka filen utan lösenord."));
        }
    }

    private static void rejectUnsupported(PDDocument doc, PdfaLevel level) throws IOException {
        if (doc.isEncrypted()) {
            throw ConversionException.unprocessable("PDF-filen är krypterad.",
                    List.of("PDF/A tillåter inte kryptering. Skicka filen utan behörighetsskydd."));
        }
        if (!doc.getSignatureDictionaries().isEmpty()) {
            throw ConversionException.unprocessable("PDF-filen är redan signerad.",
                    List.of("Konvertering skulle bryta signaturen. Konvertera före signering."));
        }
        if (level.requiresTagging() && !isTagged(doc.getDocumentCatalog())) {
            throw ConversionException.unprocessable("PDF-filen saknar taggstruktur och kan inte bli " + level.label() + ".",
                    List.of("PDF/A-1a kräver en taggad PDF. Exportera från Word med 'Dokumentstrukturtaggar för tillgänglighet',"
                            + " eller begär PDF/A-1b."));
        }
    }

    /**
     * Kräver ett strukturträd med innehåll. veraPDF godkänner PDF/A-1a med ett tomt träd, vilket
     * LibreOffice producerar i vissa lägen, men ett sådant dokument är inte tillgängligt på riktigt.
     */
    private static boolean isTagged(PDDocumentCatalog catalog) {
        PDMarkInfo markInfo = catalog.getMarkInfo();
        PDStructureTreeRoot root = catalog.getStructureTreeRoot();
        if (root == null || markInfo == null || !markInfo.isMarked()) {
            return false;
        }
        COSBase kids = root.getCOSObject().getDictionaryObject(COSName.K);
        return kids instanceof COSDictionary || (kids instanceof COSArray array && array.size() > 0);
    }

    private static void removeForbiddenCatalogEntries(PDDocument doc) {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        COSDictionary cat = catalog.getCOSObject();
        cat.removeItem(COSName.AA);
        PDDocumentNameDictionary names = catalog.getNames();
        if (names != null) {
            names.getCOSObject().removeItem(COSName.JAVA_SCRIPT);
            names.getCOSObject().removeItem(COSName.EMBEDDED_FILES);
        }
        if (cat.getDictionaryObject(COSName.OPEN_ACTION) instanceof COSDictionary action
                && !"GoTo".equals(action.getNameAsString(COSName.S))) {
            cat.removeItem(COSName.OPEN_ACTION);
        }
        PDAcroForm form = catalog.getAcroForm(null);
        if (form != null) {
            form.getCOSObject().removeItem(COSName.XFA);
        }
    }

    private static void ensureOutputIntent(PDDocument doc) throws IOException {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        for (PDOutputIntent intent : catalog.getOutputIntents()) {
            if (GTS_PDFA1.equals(intent.getCOSObject().getCOSName(COSName.S))) {
                return;
            }
        }
        try (InputStream icc = new ByteArrayInputStream(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData())) {
            PDOutputIntent intent = new PDOutputIntent(doc, icc);
            intent.setInfo("sRGB IEC61966-2.1");
            intent.setOutputCondition("sRGB IEC61966-2.1");
            intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
            intent.setRegistryName("http://www.color.org");
            catalog.addOutputIntent(intent);
        }
    }

    private static void ensureDocumentId(PDDocument doc) {
        COSArray id = doc.getDocument().getDocumentID();
        if (id != null && id.size() == 2) {
            return;
        }
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        COSArray newId = new COSArray();
        newId.add(new COSString(bytes));
        newId.add(new COSString(bytes));
        doc.getDocument().setDocumentID(newId);
    }

    /**
     * Bygger XMP från grunden utifrån Info-ordlistan. LibreOffice skriver ibland datum som inte
     * stämmer mellan Info och XMP, eller ogiltiga datum som 0000-00-00, så befintlig XMP läses inte.
     */
    private static void writeMetadata(PDDocument doc, PdfaLevel level) throws IOException {
        PDDocumentInformation info = doc.getDocumentInformation();
        Calendar now = Calendar.getInstance();
        Calendar created = info.getCreationDate() != null ? info.getCreationDate() : now;
        info.setCreationDate(created);
        info.setModificationDate(now);
        info.setTrapped(null);

        XMPMetadata xmp = XMPMetadata.createXMPMetadata();
        XMPBasicSchema basic = xmp.createAndAddXMPBasicSchema();
        basic.setCreateDate(created);
        basic.setModifyDate(now);
        if (info.getCreator() != null) {
            basic.setCreatorTool(info.getCreator());
        }
        AdobePDFSchema pdf = xmp.createAndAddAdobePDFSchema();
        if (info.getProducer() != null) {
            pdf.setProducer(info.getProducer());
        }
        if (info.getKeywords() != null) {
            pdf.setKeywords(info.getKeywords());
        }
        DublinCoreSchema dc = xmp.createAndAddDublinCoreSchema();
        if (info.getTitle() != null) {
            dc.setTitle(info.getTitle());
        }
        if (info.getAuthor() != null) {
            dc.addCreator(info.getAuthor());
        }
        if (info.getSubject() != null) {
            dc.addDescription(null, info.getSubject());
        }
        PDFAIdentificationSchema id = xmp.createAndAddPDFAIdentificationSchema();
        id.setPart(1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            id.setConformance(level.conformance());
            new XmpSerializer().serialize(xmp, out, true);
        } catch (Exception e) {
            throw new IOException("Kunde inte skriva XMP-metadata", e);
        }
        PDMetadata metadata = new PDMetadata(doc);
        metadata.importXMPMetadata(out.toByteArray());
        doc.getDocumentCatalog().setMetadata(metadata);
    }

    /**
     * Går igenom alla resurser (sidor, formulär-XObjects, mönster, Type3-fonter, annoteringar),
     * rättar det som går och samlar det som inte går.
     */
    private static final class Pass {
        private final PDDocument doc;
        private final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<COSBase, PDImageXObject> flattenedImages = new IdentityHashMap<>();
        private final Set<String> missingFonts = new LinkedHashSet<>();
        private final Set<String> transparency = new LinkedHashSet<>();
        private int pageNumber;

        Pass(PDDocument doc) {
            this.doc = doc;
        }

        void run() throws IOException {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm(null);
            if (form != null && form.getNeedAppearances()) {
                form.refreshAppearances();
                form.setNeedAppearances(false);
            }
            for (PDPage page : doc.getPages()) {
                pageNumber++;
                page.getCOSObject().removeItem(COSName.AA);
                removeTransparencyGroup(page.getCOSObject());
                visit(page.getResources());
                fixAnnotations(page);
            }
        }

        void rejectIfProblems() {
            List<String> details = new ArrayList<>();
            missingFonts.forEach(font -> details.add("Fonten '" + font + "' är inte inbäddad."));
            transparency.forEach(where -> details.add("Transparens " + where + " (tillåts inte i PDF/A-1)."));
            if (!details.isEmpty()) {
                throw ConversionException.unprocessable("PDF-filen kan inte göras om till PDF/A-1 utan att utseendet ändras.", details);
            }
        }

        private void visit(PDResources resources) throws IOException {
            if (resources == null || !visited.add(resources.getCOSObject())) {
                return;
            }
            for (COSName name : resources.getExtGStateNames()) {
                checkExtGState(resources.getExtGState(name));
            }
            for (COSName name : resources.getFontNames()) {
                PDFont font = resources.getFont(name);
                if (font != null) {
                    checkFont(font);
                }
            }
            for (COSName name : resources.getPatternNames()) {
                PDAbstractPattern pattern = resources.getPattern(name);
                if (pattern instanceof PDTilingPattern tiling) {
                    visit(tiling.getResources());
                }
            }
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDImageXObject image) {
                    fixImage(resources, name, image);
                } else if (xobject instanceof PDFormXObject form) {
                    removeTransparencyGroup(form.getCOSObject());
                    visit(form.getResources());
                }
            }
        }

        private void checkExtGState(PDExtendedGraphicsState gs) {
            if (gs == null) {
                return;
            }
            COSDictionary dict = gs.getCOSObject();
            boolean alpha = isBelowOne(dict.getDictionaryObject(COSName.CA)) || isBelowOne(dict.getDictionaryObject(COSName.CA_NS));
            COSBase smask = dict.getDictionaryObject(COSName.SMASK);
            boolean softMask = smask != null && !COSName.NONE.equals(smask);
            COSBase bm = dict.getDictionaryObject(COSName.BM);
            boolean blend = bm instanceof COSName mode && !mode.getName().equals("Normal") && !mode.getName().equals("Compatible");
            if (alpha || softMask || blend) {
                transparency.add("på sida " + pageNumber);
            }
        }

        private static boolean isBelowOne(COSBase value) {
            return value instanceof COSNumber number && number.floatValue() < 1f;
        }

        private static void removeTransparencyGroup(COSDictionary dict) {
            if (dict.getDictionaryObject(COSName.GROUP) instanceof COSDictionary group
                    && COSName.TRANSPARENCY.equals(group.getCOSName(COSName.S))) {
                dict.removeItem(COSName.GROUP);
            }
        }

        /**
         * Bilder med alfakanal (SMask) läggs mot vit bakgrund. JPEG2000 och 16-bitarsbilder kodas om.
         */
        private void fixImage(PDResources resources, COSName name, PDImageXObject image) throws IOException {
            COSStream stream = image.getCOSObject();
            stream.setBoolean(COSName.INTERPOLATE, false);
            boolean hasSoftMask = stream.containsKey(COSName.SMASK);
            boolean isJpx = image.getSuffix() != null && image.getSuffix().equals("jpx");
            boolean is16Bit = image.getBitsPerComponent() > 8;
            if (!hasSoftMask && !isJpx && !is16Bit) {
                return;
            }
            PDImageXObject replacement = flattenedImages.get(stream);
            if (replacement == null) {
                BufferedImage source = image.getImage();
                BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(source, 0, 0, null);
                g.dispose();
                replacement = "jpg".equals(image.getSuffix())
                        ? JPEGFactory.createFromImage(doc, rgb, 0.92f)
                        : LosslessFactory.createFromImage(doc, rgb);
                flattenedImages.put(stream, replacement);
            }
            resources.put(name, replacement);
        }

        private void checkFont(PDFont font) throws IOException {
            if (font instanceof PDType3Font type3) {
                visit(type3.getResources());
                return;
            }
            if (!font.isEmbedded()) {
                missingFonts.add(font.getName());
                return;
            }
            if (font instanceof PDType0Font type0) {
                ensureCidSet(type0.getDescendantFont());
            }
        }

        /** PDF/A-1 kräver CIDSet för delmängder av CID-fonter. Word och Chromium utelämnar det. */
        private void ensureCidSet(PDCIDFont cidFont) throws IOException {
            PDFontDescriptor descriptor = cidFont.getFontDescriptor();
            if (descriptor == null || descriptor.getCIDSet() != null || !isSubset(cidFont.getName())) {
                return;
            }
            BitSet cids = presentCids(cidFont);
            if (cids == null) {
                return;
            }
            byte[] bits = new byte[(cids.length() + 7) / 8];
            for (int cid = cids.nextSetBit(0); cid >= 0; cid = cids.nextSetBit(cid + 1)) {
                bits[cid / 8] |= (byte) (0x80 >> (cid % 8));
            }
            descriptor.setCIDSet(new PDStream(doc, new ByteArrayInputStream(bits), COSName.FLATE_DECODE));
        }

        private static boolean isSubset(String name) {
            return name != null && name.length() > 7 && name.charAt(6) == '+'
                    && name.substring(0, 6).chars().allMatch(c -> c >= 'A' && c <= 'Z');
        }

        private static BitSet presentCids(PDCIDFont cidFont) throws IOException {
            BitSet cids = new BitSet();
            if (cidFont instanceof PDCIDFontType2 type2) {
                TrueTypeFont ttf = type2.getTrueTypeFont();
                if (ttf == null) {
                    return null;
                }
                // veraPDF räknar varje glyfplats i den inbäddade fonten som närvarande, även tomma.
                BitSet gids = new BitSet();
                gids.set(0, ttf.getNumberOfGlyphs());
                COSBase map = cidFont.getCOSObject().getDictionaryObject(COSName.CID_TO_GID_MAP);
                if (map instanceof COSStream mapStream) {
                    byte[] bytes;
                    try (InputStream in = mapStream.createInputStream()) {
                        bytes = in.readAllBytes();
                    }
                    for (int cid = 0; cid < bytes.length / 2; cid++) {
                        int gid = ((bytes[2 * cid] & 0xff) << 8) | (bytes[2 * cid + 1] & 0xff);
                        if (gids.get(gid)) {
                            cids.set(cid);
                        }
                    }
                } else {
                    cids.or(gids);
                }
                return cids;
            }
            if (cidFont instanceof PDCIDFontType0 type0) {
                CFFFont cff = type0.getCFFFont();
                if (cff == null) {
                    return null;
                }
                for (int gid = 0; gid < cff.getNumCharStrings(); gid++) {
                    cids.set(cff instanceof CFFCIDFont ? cff.getCharset().getCIDForGID(gid) : gid);
                }
                return cids;
            }
            return null;
        }

        private void fixAnnotations(PDPage page) throws IOException {
            List<PDAnnotation> annotations = page.getAnnotations();
            List<PDAnnotation> kept = new ArrayList<>(annotations.size());
            for (PDAnnotation annotation : annotations) {
                COSDictionary dict = annotation.getCOSObject();
                String subtype = annotation.getSubtype();
                if (subtype != null && FORBIDDEN_ANNOTATIONS.contains(subtype)) {
                    continue;
                }
                if (!"Popup".equals(subtype)) {
                    int flags = annotation.getAnnotationFlags();
                    flags = (flags | ANNOT_PRINT) & ~(ANNOT_INVISIBLE | ANNOT_HIDDEN | ANNOT_NO_VIEW | ANNOT_TOGGLE_NO_VIEW);
                    annotation.setAnnotationFlags(flags);
                }
                if (dict.containsKey(COSName.CA)) {
                    dict.setFloat(COSName.CA, 1f);
                }
                dict.removeItem(COSName.AA);
                if (dict.getDictionaryObject(COSName.A) instanceof COSDictionary action
                        && action.getCOSName(COSName.S) != null
                        && FORBIDDEN_ACTIONS.contains(action.getCOSName(COSName.S).getName())) {
                    dict.removeItem(COSName.A);
                }
                PDAppearanceDictionary appearance = annotation.getAppearance();
                if (appearance != null) {
                    // PDF/A-1: utseendeordlistan får bara innehålla N.
                    appearance.getCOSObject().removeItem(COSName.R);
                    appearance.getCOSObject().removeItem(COSName.D);
                    visitAppearance(appearance.getNormalAppearance());
                }
                kept.add(annotation);
            }
            if (kept.size() != annotations.size()) {
                page.setAnnotations(kept);
            }
        }

        private void visitAppearance(PDAppearanceEntry entry) throws IOException {
            if (entry == null) {
                return;
            }
            if (entry.isStream()) {
                PDAppearanceStream stream = entry.getAppearanceStream();
                removeTransparencyGroup(stream.getCOSObject());
                visit(stream.getResources());
            } else {
                for (PDAppearanceStream stream : entry.getSubDictionary().values()) {
                    removeTransparencyGroup(stream.getCOSObject());
                    visit(stream.getResources());
                }
            }
        }
    }
}

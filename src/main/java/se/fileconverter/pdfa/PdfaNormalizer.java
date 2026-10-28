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
import java.util.Optional;
import java.util.Set;
import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
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
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
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
 * Gör en befintlig PDF till PDF/A utan att rendera om innehållet.
 *
 * <p>Rättar det som går att rätta mekaniskt (metadata, färgprofil, CIDSet, otillåtna actions) och
 * avvisar det som inte går att rätta utan att ändra hur dokumentet ser ut: kryptering, signaturer,
 * saknade fonter och, för a-nivåerna, avsaknad av taggstruktur. Resultatet ska alltid valideras efteråt.
 *
 * <p>För PDF/A-1, som bygger på PDF 1.4, görs mer: bilder med alfakanal plattas ut via
 * {@link TransparentImageFlattener}, JPEG2000, 16-bitarsbilder och ICC v4 görs om, xref-strömmar tas
 * bort och verklig transparens avvisas. PDF/A-2 och 3 tillåter allt det, så där lämnas innehållet orört.
 */
@Component
public class PdfaNormalizer {

    private static final COSName GTS_PDFA1 = COSName.getPDFName("GTS_PDFA1");
    /** Större bilder än så packas inte upp (4 byte per pixel), så att en enda fil inte kan ta slut på minnet. */
    public static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final Set<String> FORBIDDEN_ANNOTATIONS = Set.of("FileAttachment", "Sound", "Movie", "3D", "Screen", "RichMedia");
    private static final Set<String> FORBIDDEN_ACTIONS = Set.of("Launch", "Sound", "Movie", "ResetForm", "ImportData", "JavaScript");
    /** En bfchar-rad i en ToUnicode-CMap som pekar på Symbol-fontens del av det privata området. */
    private static final java.util.regex.Pattern SYMBOL_PUA =
            java.util.regex.Pattern.compile("(<[0-9A-Fa-f]{2,4}>\\s*)<([Ff]0[2-9A-Fa-f][0-9A-Fa-f])>");
    private static final int ANNOT_INVISIBLE = 1, ANNOT_HIDDEN = 2, ANNOT_PRINT = 4, ANNOT_NO_VIEW = 32, ANNOT_TOGGLE_NO_VIEW = 256;

    /**
     * @param pdf      den normaliserade filen
     * @param level    nivån filen märktes med; kan vara 1b när 1a begärdes i bästa försök-läge
     * @param warnings det som inte gick att rätta i bästa försök-läge, med orsakskod först
     */
    public record Result(byte[] pdf, PdfaLevel level, List<String> warnings) {
    }

    /** Strikt: avvisar det som inte kan rättas. */
    public byte[] normalize(byte[] pdf, PdfaLevel level) {
        return normalize(pdf, level, false).pdf();
    }

    /**
     * @param bestEffort som Gotenberg: rätta allt som går och lämna resten, i stället för att avvisa.
     *                   Krypterade och signerade filer samt för stora bilder avvisas ändå.
     */
    public Result normalize(byte[] pdf, PdfaLevel level, boolean bestEffort) {
        try (PDDocument doc = load(pdf)) {
            List<String> warnings = new ArrayList<>();
            PdfaLevel effective = rejectUnsupported(doc, level, bestEffort, warnings);
            if (!level.allowsTransparency()) {
                TransparentImageFlattener.flatten(doc);
            }
            int intentComponents = ensureOutputIntent(doc, level);
            Pass pass = new Pass(doc, intentComponents, level);
            pass.run();
            if (bestEffort) {
                warnings.addAll(pass.problems());
            } else {
                pass.rejectIfProblems();
            }

            removeForbiddenCatalogEntries(doc);
            ensureDocumentId(doc);
            writeMetadata(doc, effective);

            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length);
            if (level.part() == 1) {
                // PDF/A-1 bygger på PDF 1.4: inga xref- eller objektströmmar.
                doc.save(out, CompressParameters.NO_COMPRESSION);
            } else {
                // PDF/A-2 och 3 bygger på PDF 1.7, som tillåter objektströmmar.
                if (doc.getVersion() < 1.7f) {
                    doc.setVersion(1.7f);
                }
                doc.save(out, CompressParameters.DEFAULT_COMPRESSION);
            }
            return new Result(out.toByteArray(), effective, List.copyOf(warnings));
        } catch (IOException e) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_CONTENT, ConversionException.Reason.UNREADABLE_PDF,
                    "PDF-filen kunde inte bearbetas: " + e.getMessage(), List.of(), e);
        }
    }

    private static PDDocument load(byte[] pdf) throws IOException {
        try {
            return Loader.loadPDF(pdf);
        } catch (InvalidPasswordException e) {
            throw ConversionException.unprocessable(ConversionException.Reason.ENCRYPTED, "PDF-filen är lösenordsskyddad.",
                    List.of("PDF/A tillåter inte kryptering. Skicka filen utan lösenord."));
        }
    }

    private static PdfaLevel rejectUnsupported(PDDocument doc, PdfaLevel level, boolean bestEffort, List<String> warnings)
            throws IOException {
        if (doc.isEncrypted()) {
            throw ConversionException.unprocessable(ConversionException.Reason.ENCRYPTED, "PDF-filen är krypterad.",
                    List.of("PDF/A tillåter inte kryptering. Skicka filen utan behörighetsskydd."));
        }
        if (!doc.getSignatureDictionaries().isEmpty()) {
            throw ConversionException.unprocessable(ConversionException.Reason.SIGNED, "PDF-filen är redan signerad.",
                    List.of("Konvertering skulle bryta signaturen. Konvertera före signering."));
        }
        if (level.requiresTagging()) {
            Optional<String> problem = taggingProblem(doc);
            PdfaLevel fallback = level.untaggedFallback();
            if (problem.isPresent() && bestEffort) {
                warnings.add("UNTAGGED: " + problem.get() + " Filen blev " + fallback.label() + " i stället för "
                        + level.label() + ".");
                return fallback;
            }
            if (problem.isPresent()) {
                throw ConversionException.unprocessable(ConversionException.Reason.UNTAGGED, "PDF-filen saknar taggstruktur och kan inte bli " + level.label() + ".",
                        List.of(problem.get(), level.label() + " kräver en taggad PDF. Exportera från Word med 'Dokumentstrukturtaggar för"
                                + " tillgänglighet', eller begär " + fallback.label() + "."));
            }
        }
        return level;
    }

    /**
     * Om filen innehåller verklig transparens (halvgenomskinliga ytor, mjuka masker, blandningslägen)
     * som PDF/A-1 inte tillåter. Bilder med alfakanal räknas inte; de plattas ut av normaliseringen.
     */
    public static boolean hasTransparency(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PDPage page : doc.getPages()) {
                if (hasTransparency(page.getResources(), visited)) {
                    return true;
                }
                for (PDAnnotation annotation : page.getAnnotations()) {
                    PDAppearanceStream appearance = annotation.getNormalAppearanceStream();
                    if (appearance != null && hasTransparency(appearance.getResources(), visited)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasTransparency(PDResources resources, Set<COSBase> visited) throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) {
            return false;
        }
        for (COSName name : resources.getExtGStateNames()) {
            PDExtendedGraphicsState gs = resources.getExtGState(name);
            if (gs != null && isTransparent(gs.getCOSObject())) {
                return true;
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDFormXObject form && hasTransparency(form.getResources(), visited)) {
                return true;
            }
        }
        for (COSName name : resources.getPatternNames()) {
            if (resources.getPattern(name) instanceof PDTilingPattern tiling && hasTransparency(tiling.getResources(), visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransparent(COSDictionary gs) {
        boolean alpha = isBelowOne(gs.getDictionaryObject(COSName.CA)) || isBelowOne(gs.getDictionaryObject(COSName.CA_NS));
        COSBase smask = gs.getDictionaryObject(COSName.SMASK);
        boolean softMask = smask != null && !COSName.NONE.equals(smask);
        COSBase bm = gs.getDictionaryObject(COSName.BM);
        boolean blend = bm instanceof COSName mode && !mode.getName().equals("Normal") && !mode.getName().equals("Compatible");
        return alpha || softMask || blend;
    }

    private static boolean isBelowOne(COSBase value) {
        return value instanceof COSNumber number && number.floatValue() < 1f;
    }

    /** Varför filen inte duger som underlag för en a-nivå (t.ex. PDF/A-1a), eller tomt om den är taggad hela vägen. */
    public static Optional<String> taggingProblem(byte[] pdf) {
        try (PDDocument doc = load(pdf)) {
            return taggingProblem(doc);
        } catch (IOException e) {
            return Optional.of("Filen kunde inte läsas: " + e.getMessage());
        }
    }

    private static Optional<String> taggingProblem(PDDocument doc) throws IOException {
        if (!isTagged(doc.getDocumentCatalog())) {
            return Optional.of("Filen har ingen taggstruktur.");
        }
        List<Integer> untagged = untaggedPages(doc);
        if (!untagged.isEmpty()) {
            String pages = untagged.stream().limit(10).map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))
                    + (untagged.size() > 10 ? " m.fl." : "");
            return Optional.of("Sida " + pages + " saknar taggar, t.ex. efter sammanslagning med en otaggad fil.");
        }
        return Optional.empty();
    }

    private static final Set<String> DRAWING_OPERATORS = Set.of("Tj", "TJ", "'", "\"", "Do", "BI", "sh",
            "f", "F", "f*", "B", "B*", "b", "b*", "S", "s");

    /**
     * Sidor (1-baserade) som ritar något men saknar märkt innehåll. veraPDF godkänner PDF/A-1a även
     * när bara en del av dokumentet är taggat, t.ex. när en otaggad fil slagits ihop med en taggad.
     */
    private static List<Integer> untaggedPages(PDDocument doc) throws IOException {
        List<Integer> untagged = new ArrayList<>();
        int number = 0;
        for (PDPage page : doc.getPages()) {
            number++;
            if (page.getCOSObject().containsKey(COSName.STRUCT_PARENTS) || !page.hasContents()) {
                continue;
            }
            boolean draws = false;
            boolean marked = false;
            PDFStreamParser parser = new PDFStreamParser(page);
            for (Object token = parser.parseNextToken(); token != null && !marked; token = parser.parseNextToken()) {
                if (token instanceof Operator operator) {
                    String name = operator.getName();
                    marked = name.equals("BDC") || name.equals("BMC");
                    draws |= DRAWING_OPERATORS.contains(name);
                }
            }
            if (draws && !marked) {
                untagged.add(number);
            }
        }
        return untagged;
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

    /**
     * Ser till att det finns exakt en PDF/A-utdataprofil (PDF/A tillåter inte olika profiler), för
     * PDF/A-1 med en ICC-profil i version 2, och returnerar profilens antal färgkomponenter.
     */
    private static int ensureOutputIntent(PDDocument doc, PdfaLevel level) throws IOException {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        List<PDOutputIntent> intents = catalog.getOutputIntents();
        PDOutputIntent kept = intents.stream()
                .filter(intent -> GTS_PDFA1.equals(intent.getCOSObject().getCOSName(COSName.S)))
                .filter(intent -> intent.getCOSObject().getDictionaryObject(COSName.DEST_OUTPUT_PROFILE) instanceof COSStream)
                .findFirst().orElse(null);
        if (kept != null) {
            if (intents.size() > 1) {
                catalog.setOutputIntents(List.of(kept));
            }
            COSStream profile = (COSStream) kept.getCOSObject().getDictionaryObject(COSName.DEST_OUTPUT_PROFILE);
            if (level.part() == 1 && ColorProfiles.tooNew(profile)) {
                ColorProfiles.downgrade(profile);
            }
            return ColorProfiles.components(profile);
        }
        catalog.setOutputIntents(new ArrayList<>());
        try (InputStream icc = new ByteArrayInputStream(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData())) {
            PDOutputIntent intent = new PDOutputIntent(doc, icc);
            intent.setInfo("sRGB IEC61966-2.1");
            intent.setOutputCondition("sRGB IEC61966-2.1");
            intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
            intent.setRegistryName("http://www.color.org");
            catalog.addOutputIntent(intent);
        }
        return 3;
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
        id.setPart(level.part());

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
        private final Set<COSBase> checkedProfiles = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<COSBase> checkedCMaps = Collections.newSetFromMap(new IdentityHashMap<>());
        private final int intentComponents;
        /** PDF/A-1: transparens, JPEG2000, 16-bitarsbilder och ICC v4 måste rättas eller avvisas. */
        private final boolean part1;
        /** PDF/A-2a och 3a: tecken i Unicodes privata område (PUA) kräver ActualText eller en riktig mappning. */
        private final boolean fixPrivateUse;
        private final String levelLabel;
        private COSArray defaultColorSpace;
        private int pageNumber;

        Pass(PDDocument doc, int intentComponents, PdfaLevel level) {
            this.doc = doc;
            this.intentComponents = intentComponents;
            this.part1 = level.part() == 1;
            this.fixPrivateUse = level.part() >= 2 && level.requiresTagging();
            this.levelLabel = level.label();
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
                if (page.getResources() == null) {
                    page.setResources(new PDResources());
                }
                visit(page.getResources());
                fixAnnotations(page);
            }
        }

        /** Det som inte gick att rätta, med orsakskod först, för bästa försök-läget. */
        List<String> problems() {
            List<String> problems = new ArrayList<>();
            missingFonts.forEach(font -> problems.add("MISSING_FONT: Fonten '" + font + "' är inte inbäddad."));
            transparency.forEach(where -> problems.add("TRANSPARENCY: Transparens " + where + " (tillåts inte i " + levelLabel + ")."));
            return problems;
        }

        void rejectIfProblems() {
            List<String> details = new ArrayList<>();
            missingFonts.forEach(font -> details.add("Fonten '" + font + "' är inte inbäddad."));
            transparency.forEach(where -> details.add("Transparens " + where + " (tillåts inte i " + levelLabel + ")."));
            if (!details.isEmpty()) {
                throw ConversionException.unprocessable(missingFonts.isEmpty() ? ConversionException.Reason.TRANSPARENCY : ConversionException.Reason.MISSING_FONT,
                        "PDF-filen kan inte göras om till " + levelLabel + " utan att utseendet ändras.", details);
            }
        }

        private void visit(PDResources resources) throws IOException {
            if (resources == null || !visited.add(resources.getCOSObject())) {
                return;
            }
            fixColorSpaces(resources);
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
                    fixIcc(image.getCOSObject().getDictionaryObject(COSName.COLORSPACE));
                    fixImage(resources, name, image);
                } else if (xobject instanceof PDFormXObject form) {
                    removeTransparencyGroup(form.getCOSObject());
                    visit(form.getResources());
                }
            }
        }

        /**
         * PDF/A-1 tillåter bara enhetsfärger (DeviceRGB/DeviceCMYK) som stämmer med utdataprofilen,
         * eller när en standardfärgrymd (DefaultRGB/DefaultCMYK) anger hur de ska tolkas. Lägger till
         * den som saknas, så att t.ex. CMYK-loggor i en RGB-fil behåller sina färger. Byter också
         * ICC-profiler i version 4, som PDF/A-1 inte godtar, mot version 2.
         */
        private void fixColorSpaces(PDResources resources) throws IOException {
            COSDictionary dict = resources.getCOSObject();
            COSDictionary colorSpaces = dict.getCOSDictionary(COSName.COLORSPACE);
            if (colorSpaces == null) {
                colorSpaces = new COSDictionary();
                dict.setItem(COSName.COLORSPACE, colorSpaces);
            }
            for (COSName name : colorSpaces.keySet()) {
                fixIcc(colorSpaces.getDictionaryObject(name));
            }
            COSName defaultName = intentComponents == 4 ? COSName.DEFAULT_RGB : COSName.DEFAULT_CMYK;
            if (intentComponents != 1 && !colorSpaces.containsKey(defaultName)) {
                if (defaultColorSpace == null) {
                    defaultColorSpace = intentComponents == 4
                            ? ColorProfiles.iccBased(doc, ColorProfiles.SRGB, 3)
                            : ColorProfiles.iccBased(doc, ColorProfiles.CMYK, 4);
                }
                colorSpaces.setItem(defaultName, defaultColorSpace);
            }
        }

        private void fixIcc(COSBase colorSpace) throws IOException {
            if (!(colorSpace instanceof COSArray array) || array.size() < 2 || !(array.getObject(0) instanceof COSName family)) {
                return;
            }
            if (COSName.ICCBASED.equals(family) && array.getObject(1) instanceof COSStream profile && part1 && checkedProfiles.add(profile)) {
                if (ColorProfiles.tooNew(profile)) {
                    ColorProfiles.downgrade(profile);
                }
            } else if (COSName.INDEXED.equals(family)) {
                fixIcc(array.getObject(1));
            } else if ((COSName.SEPARATION.equals(family) || COSName.DEVICEN.equals(family)) && array.size() > 2) {
                fixIcc(array.getObject(2));
            }
        }

        private void checkExtGState(PDExtendedGraphicsState gs) {
            if (gs == null || !part1) {
                return;
            }
            if (isTransparent(gs.getCOSObject())) {
                transparency.add("på sida " + pageNumber);
            }
        }


        private void removeTransparencyGroup(COSDictionary dict) {
            if (part1 && dict.getDictionaryObject(COSName.GROUP) instanceof COSDictionary group
                    && COSName.TRANSPARENCY.equals(group.getCOSName(COSName.S))) {
                dict.removeItem(COSName.GROUP);
            }
        }

        /**
         * Bilder med alfakanal som {@link TransparentImageFlattener} inte kunde lägga mot sin riktiga
         * bakgrund läggs mot vitt. JPEG2000 och 16-bitarsbilder kodas om.
         */
        private void fixImage(PDResources resources, COSName name, PDImageXObject image) throws IOException {
            COSStream stream = image.getCOSObject();
            stream.setBoolean(COSName.INTERPOLATE, false);
            if (!part1) {
                return;
            }
            boolean hasSoftMask = stream.containsKey(COSName.SMASK);
            boolean isJpx = image.getSuffix() != null && image.getSuffix().equals("jpx");
            boolean is16Bit = image.getBitsPerComponent() > 8;
            if (!hasSoftMask && !isJpx && !is16Bit) {
                return;
            }
            PDImageXObject replacement = flattenedImages.get(stream);
            if (replacement == null && (long) image.getWidth() * image.getHeight() > MAX_IMAGE_PIXELS) {
                throw ConversionException.unprocessable(ConversionException.Reason.IMAGE_TOO_LARGE, "PDF-filen innehåller en för stor bild.",
                        List.of("Bilden på sida " + pageNumber + " är " + image.getWidth() + " × " + image.getHeight()
                                + " pixlar och behöver göras om. Gränsen är " + MAX_IMAGE_PIXELS / 1_000_000 + " megapixel."));
            }
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
            if (fixPrivateUse) {
                mapSymbolPrivateUse(font);
            }
            if (font instanceof PDType0Font type0) {
                ensureCidSet(type0.getDescendantFont());
            } else if (font instanceof PDType1CFont || font instanceof PDType1Font) {
                ensureCharSet(font);
            }
        }

        /**
         * Word skriver punkter i listor med fonten Symbol, och ToUnicode pekar då på Unicodes privata
         * område (U+F020–U+F0FF, Symbol-tecknet + F000), t.ex. U+F0B7 för •. PDF/A-2a och 3a kräver
         * ActualText för sådana tecken; i stället pekas mappningen om till det riktiga Unicode-tecknet
         * via Symbol-kodningen, vilket också gör texten sökbar och läsbar för skärmläsare.
         */
        private void mapSymbolPrivateUse(PDFont font) throws IOException {
            if (font.getName() == null || !font.getName().toLowerCase(java.util.Locale.ROOT).contains("symbol")
                    || !(font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream toUnicode)
                    || !checkedCMaps.add(toUnicode)) {
                return;
            }
            String cmap;
            try (InputStream in = toUnicode.createInputStream()) {
                cmap = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            java.util.regex.Matcher matcher = SYMBOL_PUA.matcher(cmap);
            StringBuilder fixed = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                int code = Integer.parseInt(matcher.group(2), 16) & 0xff;
                String name = org.apache.pdfbox.pdmodel.font.encoding.SymbolEncoding.INSTANCE.getName(code);
                String unicode = name == null ? null : org.apache.pdfbox.pdmodel.font.encoding.GlyphList.getAdobeGlyphList().toUnicode(name);
                if (unicode == null || unicode.length() != 1) {
                    matcher.appendReplacement(fixed, matcher.group());
                    continue;
                }
                matcher.appendReplacement(fixed, matcher.group(1) + String.format("<%04X>", (int) unicode.charAt(0)));
                changed = true;
            }
            if (changed) {
                matcher.appendTail(fixed);
                try (java.io.OutputStream out = toUnicode.createOutputStream(COSName.FLATE_DECODE)) {
                    out.write(fixed.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                }
            }
        }

        /**
         * PDF/A-1 kräver CharSet för delmängder av Type 1-fonter. Saknas ofta när en OpenType/CFF-font
         * bäddats in, t.ex. när LibreOffice valt en sådan som ersättning.
         */
        private static void ensureCharSet(PDFont font) throws IOException {
            PDFontDescriptor descriptor = font.getFontDescriptor();
            if (descriptor == null || descriptor.getCharSet() != null || !isSubset(font.getName())) {
                return;
            }
            List<String> names = new ArrayList<>();
            if (font instanceof PDType1CFont cff && cff.getCFFType1Font() != null) {
                CFFType1Font program = cff.getCFFType1Font();
                for (int gid = 0; gid < program.getNumCharStrings(); gid++) {
                    names.add(program.getCharset().getNameForGID(gid));
                }
            } else if (font instanceof PDType1Font type1 && type1.getType1Font() != null) {
                names.addAll(type1.getType1Font().getCharStringsDict().keySet());
            } else {
                return;
            }
            StringBuilder charSet = new StringBuilder();
            names.stream().filter(name -> name != null && !name.equals(".notdef")).sorted()
                    .forEach(name -> charSet.append('/').append(name));
            descriptor.getCOSObject().setString(COSName.getPDFName("CharSet"), charSet.toString());
        }

        /**
         * PDF/A-1 kräver CIDSet för delmängder av CID-fonter, och att den stämmer med fonten.
         * Word och Chromium utelämnar den, och andra program skriver ibland en ofullständig, så den
         * räknas alltid om.
         */
        private void ensureCidSet(PDCIDFont cidFont) throws IOException {
            PDFontDescriptor descriptor = cidFont.getFontDescriptor();
            if (descriptor == null || !isSubset(cidFont.getName())) {
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
                    // PDF/A kräver synliga, utskrivbara annoteringar. Dolt innehåll (dolda fält,
                    // granskningsanteckningar) tas bort i stället för att plötsligt synas i arkivfilen.
                    boolean hidden = (flags & (ANNOT_INVISIBLE | ANNOT_HIDDEN)) != 0
                            || ((flags & ANNOT_NO_VIEW) != 0 && (flags & ANNOT_PRINT) == 0);
                    if (hidden) {
                        continue;
                    }
                    flags = (flags | ANNOT_PRINT) & ~(ANNOT_NO_VIEW | ANNOT_TOGGLE_NO_VIEW);
                    annotation.setAnnotationFlags(flags);
                }
                if (part1 && dict.containsKey(COSName.CA)) {
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
                    // PDF/A: utseendeordlistan får bara innehålla N.
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

package se.fileconverter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.fileconverter.TestPdfs;
import se.fileconverter.pdfa.PdfaLevel;
import se.fileconverter.pdfa.PdfaValidator;

/**
 * Hela kedjan genom API:t med riktig LibreOffice. Varje PDF/A-svar valideras om med veraPDF här,
 * oberoende av tjänstens egen validering.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("libreOfficeInstalled")
class ConversionControllerTest {

    @TempDir
    static Path assets;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("converter.assets.dir", () -> assets.toString());
    }

    @Autowired
    MockMvc mvc;

    private final PdfaValidator validator = new PdfaValidator();

    /**
     * Villkor för integrationstesterna. Med {@code REQUIRE_LIBREOFFICE=true} (som i CI) fallerar de i
     * stället för att hoppas över när LibreOffice saknas, så att ett trasigt CI inte ser grönt ut.
     */
    static boolean libreOfficeInstalled() {
        boolean installed = new java.io.File("/usr/bin/soffice").canExecute()
                || new java.io.File("/usr/lib/libreoffice/program/soffice").canExecute();
        if (!installed && Boolean.parseBoolean(System.getenv("REQUIRE_LIBREOFFICE"))) {
            throw new IllegalStateException("REQUIRE_LIBREOFFICE=true men LibreOffice (soffice) saknas.");
        }
        return installed;
    }

    @Test
    void wordToPdfa1a() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert")
                        .file(file("beslut.docx"))
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Pdfa-Level", "PDF/A-1a"))
                .andExpect(header().string("X-Pdfa-Compliant", "true"))
                .andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        // PDF/A-versionen ska se ut som en vanlig PDF av samma dokument (logga direkt på vit sida).
        byte[] plain = mvc.perform(multipart("/forms/libreoffice/convert").file(file("beslut.docx")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(differingPixels(plain, pdf)).isLessThan(0.001);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"beslut.doc", "beslut.odt", "presentation.pptx"})
    void otherOfficeFormatsBecomeTaggedPdfa1a(String fixture) throws Exception {
        byte[] pdf = toPdfa1a(fixture);

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        byte[] plain = mvc.perform(multipart("/forms/libreoffice/convert").file(file(fixture)))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(differingPixels(plain, pdf)).isLessThan(0.001);
    }

    @Test
    void slideWithSemiTransparentShapeIsFlattenedToValidPdfa1b() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert").file(file("presentation-transparens.pptx"))
                        .param("pdfa", "PDF/A-1b"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Pdfa-Compliant", "true"))
                .andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1B);
        byte[] plain = mvc.perform(multipart("/forms/libreoffice/convert").file(file("presentation-transparens.pptx")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(differingPixels(plain, pdf)).as("utplattningen ska se ut som originalet").isLessThan(0.02);
    }

    @Test
    void transparentSlideRequestedAs1aBecomesHonestlyLabelled1b() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert").file(file("presentation-transparens.pptx"))
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Pdfa-Level", "PDF/A-1b"))
                .andExpect(header().string("X-Pdfa-Compliant", "false"))
                // Fler orsaker kan tillkomma med andra LibreOffice-versioner; UNTAGGED är det som måste finnas.
                .andExpect(header().string("X-Pdfa-Problems", org.hamcrest.Matchers.containsString("UNTAGGED")))
                .andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1B);
    }

    @Test
    void spreadsheetBecomesPdfa1b() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert").file(file("avgifter.xlsx")).param("pdfa", "PDF/A-1b"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Pdfa-Compliant", "true"))
                .andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1B);
    }

    @Test
    void calibriIsReplacedByMetricCompatibleCarlito() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert")
                        .file(file("beslut-calibri.docx"))
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(fontNames(pdf)).anyMatch(name -> name.contains("Carlito"));
        assertCompliant(pdf, PdfaLevel.PDFA_1A);
    }

    @Test
    void transparentLogoKeepsColouredCellBehindIt() throws Exception {
        byte[] original = mvc.perform(multipart("/forms/libreoffice/convert").file(file("logga-pa-farg.docx")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        byte[] pdfa = toPdfa1a("logga-pa-farg.docx");

        assertCompliant(pdfa, PdfaLevel.PDFA_1A);
        // Vanlig PDF behåller transparensen. PDF/A-versionen ska se likadan ut, inte ha vita hörn.
        assertThat(differingPixels(original, pdfa)).isLessThan(0.001);
    }

    @Test
    void templateToPdfa1a() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(file("index.html", "mall.html"))
                        .file(file("alpha.png"))
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Pdfa-Compliant", "true"))
                .andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        // Arial och Times New Roman i mallen ska ge de måttkompatibla Liberation-fonterna.
        assertThat(fontNames(pdf)).anyMatch(n -> n.contains("LiberationSans")).anyMatch(n -> n.contains("LiberationSerif"));
    }

    @Test
    void templatePaperFieldsOverrideTemplate() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(file("index.html", "mall.html"))
                        .file(file("alpha.png"))
                        .param("paperWidth", "8.5")
                        .param("paperHeight", "11")
                        .param("landscape", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(0);
            assertThat(page.getMediaBox().getWidth()).isCloseTo(11 * 72, org.assertj.core.data.Offset.offset(1f));
            assertThat(page.getMediaBox().getHeight()).isCloseTo(8.5f * 72, org.assertj.core.data.Offset.offset(1f));
        }
    }

    @Test
    void customerPdfToPdfa1b() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/pdfengines/convert")
                        .file(new MockMultipartFile("files", "kund.pdf", "application/pdf", TestPdfs.untaggedWithAlphaImage()))
                        .param("pdfa", "PDF/A-1b"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1B);
    }

    @Test
    void customerPdfWithTransparencyIsRejectedWithExplanation() throws Exception {
        mvc.perform(multipart("/forms/pdfengines/convert")
                        .file(new MockMultipartFile("files", "kund.pdf", "application/pdf", TestPdfs.withTransparency()))
                        .param("pdfa", "PDF/A-1b").param("pdfaStrict", "true"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.containsString("Transparens")));
    }

    @Test
    void bestEffortIsTheDefaultAndReportsWhatIsLeft() throws Exception {
        mvc.perform(multipart("/forms/pdfengines/convert")
                        .file(new MockMultipartFile("files", "kund.pdf", "application/pdf", TestPdfs.withTransparency()))
                        .param("pdfa", "PDF/A-1b"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Pdfa-Compliant", "false"))
                .andExpect(header().string("X-Pdfa-Problems", org.hamcrest.Matchers.containsString("TRANSPARENCY")));
    }

    @Test
    void mergeOfTaggedDocumentsStaysPdfa1a() throws Exception {
        byte[] first = toPdfa1a("beslut.docx");
        byte[] second = toPdfa1a("bilaga.docx");

        byte[] merged = mvc.perform(multipart("/forms/pdfengines/merge")
                        .file(new MockMultipartFile("files", "1.pdf", "application/pdf", first))
                        .file(new MockMultipartFile("files", "2.pdf", "application/pdf", second))
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertCompliant(merged, PdfaLevel.PDFA_1A);
        try (PDDocument doc = Loader.loadPDF(merged)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(Loader.loadPDF(first).getNumberOfPages() + Loader.loadPDF(second).getNumberOfPages());
        }
    }

    @Test
    void severalFilesWithoutMergeGiveZip() throws Exception {
        byte[] zip = mvc.perform(multipart("/forms/libreoffice/convert")
                        .file(file("beslut.docx"))
                        .file(file("bilaga.docx")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn().getResponse().getContentAsByteArray();

        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                names.add(entry.getName());
            }
        }
        assertThat(names).containsExactly("beslut.pdf", "bilaga.pdf");
    }

    @Test
    void documentsCannotPullInLocalFilesOrUrls() throws Exception {
        Path secret = Files.createTempFile("hemlig", ".png");
        Files.write(secret, TestPdfs.fixture("alpha.png"));
        String fodt = """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" office:version="1.2" office:mimetype="application/vnd.oasis.opendocument.text">
                <office:body><office:text><text:p>Lokal fil:<draw:frame draw:name="a" text:anchor-type="as-char" svg:width="2cm" svg:height="2cm"><draw:image xlink:href="%s" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame></text:p></office:text></office:body></office:document>
                """.formatted(secret.toUri());

        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert")
                        .file(new MockMultipartFile("files", "lank.fodt", null, fodt.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getPage(0).getResources().getXObjectNames()).as("bilder i PDF:en").isEmpty();
        }
    }

    @Test
    void wordWithLandscapeSectionKeepsOrientationPerPage() throws Exception {
        byte[] pdf = toPdfa1a("staende-liggande.docx");

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        assertThat(orientations(pdf)).containsExactly("stående", "liggande", "stående");
    }

    @Test
    void templateCanSwitchToLandscapeWithNamedPage() throws Exception {
        String html = """
                <html lang="sv"><head><style>
                  @page { size: A4; }
                  @page bred { size: A4 landscape; }
                  .bilaga { page: bred; }
                </style></head><body>
                  <h1>Beslut</h1><p>Stående.</p>
                  <div class="bilaga"><h2>Bilaga</h2><p>Liggande.</p></div>
                  <div><h2>Underskrift</h2><p>Stående igen.</p></div>
                </body></html>""";
        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(new MockMultipartFile("files", "index.html", "text/html", html.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        assertThat(orientations(pdf)).containsExactly("stående", "liggande", "stående");
    }

    @Test
    void mergeKeepsEachDocumentsOrientation() throws Exception {
        byte[] merged = mvc.perform(multipart("/forms/pdfengines/merge")
                        .file(new MockMultipartFile("files", "1.pdf", "application/pdf", toPdfa1a("beslut.docx")))
                        .file(new MockMultipartFile("files", "2.pdf", "application/pdf", toPdfa1a("staende-liggande.docx")))
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertCompliant(merged, PdfaLevel.PDFA_1A);
        assertThat(orientations(merged)).containsExactly("stående", "stående", "liggande", "stående");
    }

    @Test
    void mergeToPdfa1aNamesTheUntaggedFile() throws Exception {
        mvc.perform(multipart("/forms/pdfengines/merge")
                        .file(new MockMultipartFile("files", "1-beslut.pdf", "application/pdf", toPdfa1a("beslut.docx")))
                        .file(new MockMultipartFile("files", "2-kundfil.pdf", "application/pdf", TestPdfs.untaggedWithAlphaImage()))
                        .param("pdfa", "PDF/A-1a").param("pdfaStrict", "true"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.startsWith("2-kundfil.pdf")));
    }

    // --- Gotenberg-kompatibilitet ---

    @Test
    void libreofficeLandscapeTurnsAllPages() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert").file(file("beslut.docx"))
                        .param("landscape", "true").param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        assertThat(orientations(pdf)).containsOnly("liggande");
    }

    @Test
    void libreofficeNativePageRangesKeepsTaggedPdfa1a() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert").file(file("staende-liggande.docx"))
                        .param("nativePageRanges", "2").param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        assertThat(orientations(pdf)).containsExactly("liggande");
    }

    @Test
    void templateHeaderAndFooterWithPageNumbers() throws Exception {
        String body = "<p>Stycke med text som fyller sidan.</p>".repeat(80);
        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(html("index.html", "<html lang=\"sv\"><head><title>Beslut</title><style>@page { margin: 25mm 15mm; }</style></head><body><h1>Beslut</h1>" + body + "</body></html>"))
                        .file(html("header.html", "<html><body><p style=\"font-size: 9pt\">Sidhuvud: <span class=\"title\"></span></p></body></html>"))
                        .file(html("footer.html", "<html><body><p style=\"font-size: 9pt\">Sida <span class=\"pageNumber\"></span> av <span class=\"totalPages\"></span></p></body></html>"))
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = doc.getNumberOfPages();
            assertThat(pages).isGreaterThan(1);
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            String page2 = stripper.getText(doc);
            assertThat(page2).contains("Sidhuvud: Beslut").contains("Sida 2 av " + pages);
        }
    }

    @Test
    void templateBackgroundsFollowPrintBackgroundLikeChromium() throws Exception {
        String template = "<html lang=\"sv\"><body><div style=\"background-color: #ff0000; height: 200px\">Röd ruta</div></body></html>";
        assertThat(redPixels(renderTemplate(template, "false"))).isZero();
        assertThat(redPixels(renderTemplate(template, "true"))).isPositive();
    }

    @Test
    void templateOutlineFromHeadings() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(html("index.html", "<html lang=\"sv\"><body><h1>Beslut</h1><h2>Bakgrund</h2><p>x</p><h2>Skäl</h2><p>y</p></body></html>"))
                        .param("generateDocumentOutline", "true").param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            var top = doc.getDocumentCatalog().getDocumentOutline().getFirstChild();
            assertThat(top.getTitle()).isEqualTo("Beslut");
            List<String> children = new ArrayList<>();
            top.children().forEach(child -> children.add(child.getTitle()));
            assertThat(children).containsExactly("Bakgrund", "Skäl");
        }
    }

    @Test
    void metadataEndsUpInPdfaMetadata() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert").file(file("beslut.docx")).param("pdfa", "PDF/A-1a")
                        .param("metadata", "{\"Title\": \"Beslut 2026-123\", \"Author\": \"Byggnadsnämnden\", \"Keywords\": [\"bygglov\", \"beslut\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1A);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("Beslut 2026-123");
            assertThat(doc.getDocumentInformation().getAuthor()).isEqualTo("Byggnadsnämnden");
            assertThat(doc.getDocumentInformation().getKeywords()).isEqualTo("bygglov, beslut");
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(xmp).contains("Beslut 2026-123").contains("Byggnadsnämnden");
        }
    }

    @Test
    void flattenTurnsFormFieldsIntoContent() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/pdfengines/convert")
                        .file(new MockMultipartFile("files", "ansokan.pdf", "application/pdf", TestPdfs.withFormField()))
                        .param("flatten", "true").param("pdfa", "PDF/A-1b"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        assertCompliant(pdf, PdfaLevel.PDFA_1B);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            var form = doc.getDocumentCatalog().getAcroForm(null);
            assertThat(form == null || form.getFields().isEmpty()).isTrue();
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(doc)).contains("Anna Andersson");
        }
    }

    @Test
    void fieldsWithoutEffectAreReportedNotSilentlyDropped() throws Exception {
        mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", "<html><body><p>x</p></body></html>"))
                        .param("waitDelay", "2s").param("userAgent", "x"))
                .andExpect(status().isOk())
                .andExpect(header().string(GotenbergOptions.IGNORED_HEADER, "userAgent,waitDelay"));
    }

    @Test
    void optionsThatCannotBeHonouredAreRejected() throws Exception {
        mvc.perform(multipart("/forms/libreoffice/convert").file(file("beslut.docx")).param("pdfua", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNSUPPORTED_OPTION"));
        mvc.perform(multipart("/forms/libreoffice/convert").file(file("beslut.docx")).header("Gotenberg-Webhook-Url", "http://x"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", "<p>x</p>")).param("singlePage", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void traceIdIsEchoedAndIncludedInErrors() throws Exception {
        mvc.perform(multipart("/forms/pdfengines/convert")
                        .file(new MockMultipartFile("files", "kund.pdf", "application/pdf", TestPdfs.withTransparency()))
                        .param("pdfa", "PDF/A-1b").param("pdfaStrict", "true").header("Gotenberg-Trace", "arende-4711"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().string("Gotenberg-Trace", "arende-4711"))
                .andExpect(jsonPath("$.traceId").value("arende-4711"))
                .andExpect(jsonPath("$.reason").value("TRANSPARENCY"));
    }

    private byte[] renderTemplate(String template, String printBackground) throws Exception {
        return mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", template))
                        .param("printBackground", printBackground))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private static long redPixels(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(0, 40);
            long red = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    if (((rgb >> 16) & 0xff) > 200 && ((rgb >> 8) & 0xff) < 60 && (rgb & 0xff) < 60) {
                        red++;
                    }
                }
            }
            return red;
        }
    }

    private static MockMultipartFile html(String name, String content) {
        return new MockMultipartFile("files", name, "text/html", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<String> orientations(byte[] pdf) throws IOException {
        List<String> result = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                boolean rotated = page.getRotation() % 180 != 0;
                boolean wide = page.getMediaBox().getWidth() > page.getMediaBox().getHeight();
                result.add(wide != rotated ? "liggande" : "stående");
            }
        }
        return result;
    }

    @Test
    void duplicateFileNamesAreRejectedInsteadOfLosingADocument() throws Exception {
        mvc.perform(multipart("/forms/pdfengines/merge")
                        .file(new MockMultipartFile("files", "a.pdf", "application/pdf", TestPdfs.untaggedWithAlphaImage()))
                        .file(new MockMultipartFile("files", "a.pdf", "application/pdf", TestPdfs.withStandardFont())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameBaseNameGivesUniqueZipEntries() throws Exception {
        byte[] zip = mvc.perform(multipart("/forms/pdfengines/convert")
                        .file(new MockMultipartFile("files", "doc.pdf", "application/pdf", TestPdfs.untaggedWithAlphaImage()))
                        .file(new MockMultipartFile("files", "doc.PDF", "application/pdf", TestPdfs.untaggedWithAlphaImage()))
                        .param("pdfa", "PDF/A-1b"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                names.add(entry.getName());
            }
        }
        assertThat(names).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void unsupportedPdfaLevelIsBadRequest() throws Exception {
        mvc.perform(multipart("/forms/libreoffice/convert").file(file("beslut.docx")).param("pdfa", "PDF/A-2b"))
                .andExpect(status().isBadRequest());
    }

    /** Samma form som Gotenbergs /health (F6 i gap-analysen), med LibreOffice-versionen som tillägg. */
    @Test
    void healthReportsLibreOffice() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"))
                .andExpect(jsonPath("$.details.libreoffice.status").value("up"))
                .andExpect(jsonPath("$.details.libreoffice.timestamp").isString())
                .andExpect(jsonPath("$.details.libreoffice.version").value(org.hamcrest.Matchers.startsWith("LibreOffice ")));
    }

    /** Fontpaketen i imagen (och i CI) ska ge alla inbyggda alias en målfont, annars får mallarna fel font. */
    @Test
    void builtInFontAliasesHaveTargets(@Autowired se.fileconverter.assets.FontAliasCheck aliases) {
        assertThat(aliases.missing()).isEmpty();
    }

    private byte[] toPdfa1a(String fixture) throws Exception {
        return mvc.perform(multipart("/forms/libreoffice/convert").file(file(fixture)).param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private void assertCompliant(byte[] pdf, PdfaLevel level) throws IOException {
        PdfaValidator.Report report = validator.validate(pdf, level);
        assertThat(report.violations()).isEmpty();
        assertThat(report.compliant()).isTrue();
        if (level.requiresTagging()) {
            // veraPDF godkänner 1a med tomt strukturträd, så det kontrolleras separat.
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
                assertThat(root).isNotNull();
                assertThat(root.getKids()).as("strukturträdets innehåll").isNotEmpty();
            }
        }
    }

    /** Andel pixlar på första sidan som skiljer mer än en nyans mellan två renderingar. */
    private static double differingPixels(byte[] a, byte[] b) throws IOException {
        try (PDDocument docA = Loader.loadPDF(a); PDDocument docB = Loader.loadPDF(b)) {
            BufferedImage imageA = new PDFRenderer(docA).renderImageWithDPI(0, 100);
            BufferedImage imageB = new PDFRenderer(docB).renderImageWithDPI(0, 100);
            long differing = 0;
            for (int y = 0; y < imageA.getHeight(); y++) {
                for (int x = 0; x < imageA.getWidth(); x++) {
                    int pa = imageA.getRGB(x, y);
                    int pb = imageB.getRGB(x, y);
                    for (int shift = 0; shift <= 16; shift += 8) {
                        if (Math.abs(((pa >> shift) & 0xff) - ((pb >> shift) & 0xff)) > 24) {
                            differing++;
                            break;
                        }
                    }
                }
            }
            return (double) differing / ((long) imageA.getWidth() * imageA.getHeight());
        }
    }

    private static List<String> fontNames(byte[] pdf) throws IOException {
        List<String> names = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    names.add(page.getResources().getFont(name).getName());
                }
            }
        }
        return names;
    }

    private static MockMultipartFile file(String fixture) {
        return file(fixture, fixture);
    }

    private static MockMultipartFile file(String uploadName, String fixture) {
        return new MockMultipartFile("files", uploadName, null, TestPdfs.fixture(fixture));
    }
}

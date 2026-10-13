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

    static boolean libreOfficeInstalled() {
        return new java.io.File("/usr/bin/soffice").canExecute() || new java.io.File("/usr/lib/libreoffice/program/soffice").canExecute();
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
                        .param("pdfa", "PDF/A-1b"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.containsString("Transparens")));
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
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.startsWith("2-kundfil.pdf")));
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

    @Test
    void healthReportsLibreOffice() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.libreoffice").value("up"));
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

package se.fileconverter.chromium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
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
 * HTML-routen med riktig Chromium: mallar byggda för Gotenbergs Chromium, Gotenbergs fält och
 * isoleringen från filsystem och nätverk.
 */
@SpringBootTest(properties = {"converter.office.enabled=false", "converter.chromium.job-timeout=8s"})
@AutoConfigureMockMvc
@EnabledIf("chromiumInstalled")
class ChromiumRouteTest {

    @TempDir
    static Path assets;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("converter.assets.dir", () -> assets.toString());
    }

    static boolean chromiumInstalled() {
        return ChromiumConverter.findBinary(System.getenv("CONVERTER_CHROMIUM_PATH")).isPresent();
    }

    @Autowired
    MockMvc mvc;

    @Test
    void flexTemplateIsRenderedWithChromiumWithGotenbergDefaults() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(html("index.html", "<div style='display:flex;justify-content:space-between'><span>Vänster</span><span>Höger</span></div>")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Converter-Engine", "chromium"))
                .andExpect(header().string("X-Converter-Engine-Reason", "flexbox"))
                .andReturn().getResponse().getContentAsByteArray();

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            // Som Gotenberg: Letter när inga pappersfält skickas.
            PDRectangle size = doc.getPage(0).getMediaBox();
            assertThat(size.getWidth()).isCloseTo(612, org.assertj.core.data.Offset.offset(1f));
            assertThat(size.getHeight()).isCloseTo(792, org.assertj.core.data.Offset.offset(1f));
            assertThat(new PDFTextStripper().getText(doc)).contains("Vänster").contains("Höger");
        }
    }

    @Test
    void javascriptRunsAndTaggedResultBecomesPdfa2a() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(html("index.html", "<h1>Rubrik</h1><p id='t'>väntar</p><script>document.getElementById('t').textContent = 'Skrivet av JavaScript'</script>"))
                        .param("pdfa", "PDF/A-2b"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Converter-Engine-Reason", "script"))
                .andExpect(header().string("X-Pdfa-Level", "PDF/A-2a"))
                .andExpect(header().string("X-Pdfa-Compliant", "true"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new PdfaValidator().validate(pdf, PdfaLevel.PDFA_2A).compliant()).isTrue();
        assertThat(text(pdf)).contains("Skrivet av JavaScript");
    }

    @Test
    void templateCannotReadLocalFilesOrReachTheNetwork() throws Exception {
        String hostname = Files.readString(Path.of("/etc/hostname")).trim();
        String template = "<p>Mall</p><iframe src='file:///etc/hostname'></iframe><img src='http://169.254.169.254/latest/meta-data'>"
                + "<p id='f'>inget</p><script>fetch('file:///etc/hostname').then(r => r.text())"
                + ".then(t => document.getElementById('f').textContent = t).catch(() => {})</script>";

        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", template)).param("waitDelay", "300ms"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(text(pdf)).contains("Mall").contains("inget").doesNotContain(hostname);
        mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", template)).param("failOnResourceLoadingFailed", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("RESOURCE_FAILED"));
    }

    @Test
    void filesInTheRequestAreServed() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(html("index.html", "<link rel='stylesheet' href='stil.css'><p class='x'>Stil</p><img src='logo.png'>"))
                        .file(new MockMultipartFile("files", "stil.css", "text/css", ".x { display: flex; color: rgb(200, 0, 0) }".getBytes()))
                        .file(new MockMultipartFile("files", "logo.png", "image/png", TestPdfs.fixture("alpha.png")))
                        .param("failOnResourceLoadingFailed", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Converter-Engine-Reason", "flexbox"))
                .andReturn().getResponse().getContentAsByteArray();

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getPage(0).getResources().getXObjectNames()).as("bilden").isNotEmpty();
        }
    }

    @Test
    void failOnConsoleExceptionsGivesConflictLikeGotenberg() throws Exception {
        mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", "<script>throw new Error('trasig mall')</script>"))
                        .param("failOnConsoleExceptions", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CONSOLE_EXCEPTION"))
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.containsString("trasig mall")));
    }

    @Test
    void waitForSelectorAndExpressionWaitForTheTemplate() throws Exception {
        String template = "<div id='mal'></div><script>setTimeout(() => { document.getElementById('mal').innerHTML = '<p id=klar>Klar</p>';"
                + " window.status = 'ready' }, 500)</script>";
        assertThat(text(mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", template))
                        .param("waitForSelector", "#klar"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())).contains("Klar");
        assertThat(text(mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", template))
                        .param("waitForExpression", "window.status === 'ready'"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())).contains("Klar");
    }

    @Test
    void expressionThatNeverBecomesTrueTimesOut() throws Exception {
        mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", "<p>x</p>"))
                        .param("waitForExpression", "false"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("CHROMIUM_TIMEOUT"));
    }

    @Test
    void paperFieldsHeaderFooterSinglePageAndScale() throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(html("index.html", "<p style='display:flex'>Innehåll</p>"))
                        .file(html("footer.html", "<p style='font-size:10px'>Sida <span class='pageNumber'></span> av <span class='totalPages'></span></p>"))
                        .param("paperWidth", "210mm").param("paperHeight", "297mm").param("marginBottom", "2cm").param("landscape", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDRectangle size = doc.getPage(0).getMediaBox();
            assertThat(size.getWidth()).as("A4 liggande").isCloseTo(842, org.assertj.core.data.Offset.offset(2f));
            assertThat(new PDFTextStripper().getText(doc)).contains("Sida 1 av 1");
        }

        StringBuilder longTemplate = new StringBuilder("<div style='display:flex;flex-direction:column'>");
        for (int i = 0; i < 200; i++) {
            longTemplate.append("<p>Rad ").append(i).append("</p>");
        }
        byte[] single = mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", longTemplate + "</div>"))
                        .param("singlePage", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(single)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void templateWithoutChromiumSignalsStaysOnOpenhtmltopdfUnlessForced() throws Exception {
        mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", "<p>Brev</p>")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Converter-Engine", "openhtmltopdf"));
        mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", "<p>Brev</p>")).param("converterEngine", "chromium"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Converter-Engine", "chromium"))
                .andExpect(header().string("X-Converter-Engine-Reason", "vald"));
    }

    @Test
    void pageRangeOutsideTheDocumentIsBadRequestLikeGotenberg() throws Exception {
        mvc.perform(multipart("/forms/chromium/convert/html").file(html("index.html", "<p style='display:flex'>En sida</p>"))
                        .param("nativePageRanges", "5-9"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthShowsChromium() throws Exception {
        mvc.perform(get("/health")).andExpect(jsonPath("$.details.chromium.status").value("up"));
    }

    private static MockMultipartFile html(String name, String body) {
        return new MockMultipartFile("files", name, "text/html",
                ("<!doctype html><html lang='sv'><head><meta charset='utf-8'><title>Mall</title></head><body>" + body + "</body></html>")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String text(byte[] pdf) throws java.io.IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}

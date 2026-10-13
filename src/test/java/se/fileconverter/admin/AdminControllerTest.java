package se.fileconverter.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import se.fileconverter.TestPdfs;
import se.fileconverter.assets.AssetReloader;
import se.fileconverter.assets.AssetStore;
import se.fileconverter.html.FontRegistry;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("se.fileconverter.api.ConversionControllerTest#libreOfficeInstalled")
class AdminControllerTest {

    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("admin:hemligt".getBytes(StandardCharsets.UTF_8));
    private static final String TEST_FONT = "FilkonverterTest-Regular.ttf";

    @TempDir
    static Path assets;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("converter.assets.dir", () -> assets.toString());
        registry.add("converter.admin.password", () -> "hemligt");
        registry.add("converter.assets.poll-interval", () -> "1h");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AssetStore store;
    @Autowired
    AssetReloader reloader;
    @Autowired
    FontRegistry fonts;

    @AfterEach
    void cleanUp() {
        store.fontFiles().forEach(file -> store.deleteFont(file.getFileName().toString()));
        store.saveAliases(Map.of());
        reloader.reloadIfChanged();
    }

    @Test
    void requiresPassword() throws Exception {
        mvc.perform(get("/admin/api/fonts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/api/fonts").header("Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:fel".getBytes())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/api/fonts").header("Authorization", AUTH)).andExpect(status().isOk());
    }

    @Test
    void encodedOrParameterisedPathsCannotBypassPassword() throws Exception {
        for (String path : new String[] {"/%61dmin/api/aliases", "/admin;x/api/aliases", "/admin/api;x/aliases", "/%61dmin/index.html"}) {
            mvc.perform(get(path)).andExpect(result ->
                    assertThat(result.getResponse().getStatus()).as(path).isIn(401, 404, 400));
        }
        mvc.perform(put("/%61dmin/api/aliases").header("X-Requested-With", "x")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"Arial\": \"Liberation Serif\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 404, 400));
        assertThat(store.aliases()).isEmpty();
    }

    @Test
    void mutatingRequestsRequireCsrfHeader() throws Exception {
        mvc.perform(delete("/admin/api/fonts/x.ttf").header("Authorization", AUTH)).andExpect(status().isForbidden());
    }

    @Test
    void conversionRoutesStayOpen() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void uploadedFontIsUsedByLibreOffice() throws Exception {
        assertThat(officeFonts("egen-font.docx")).noneMatch(name -> name.contains("FilkonverterTest"));

        mvc.perform(admin(multipart("/admin/api/fonts").file(font(TEST_FONT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ok").value(true))
                .andExpect(jsonPath("$[0].family").value("Filkonverter Test"));

        assertThat(officeFonts("egen-font.docx")).anyMatch(name -> name.contains("FilkonverterTest"));
    }

    @Test
    void uploadedFontAndAliasAreUsedByTemplates() throws Exception {
        mvc.perform(admin(multipart("/admin/api/fonts").file(font(TEST_FONT)))).andExpect(status().isOk());
        mvc.perform(admin(put("/admin/api/aliases")).contentType(MediaType.APPLICATION_JSON).content("{\"Garamond\": \"Filkonverter Test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.Garamond").value("Filkonverter Test"));

        byte[] pdf = mvc.perform(multipart("/forms/chromium/convert/html")
                        .file(new MockMultipartFile("files", "index.html", "text/html",
                                "<html lang=\"sv\"><body><p style=\"font-family: Garamond\">Åäö</p></body></html>".getBytes(StandardCharsets.UTF_8)))
                        .param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(fontNames(pdf)).anyMatch(name -> name.contains("FilkonverterTest"));
    }

    @Test
    void fontWhoseLicenceForbidsEmbeddingIsRejected() throws Exception {
        mvc.perform(admin(multipart("/admin/api/fonts").file(font("FilkonverterSparrad-Regular.ttf"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ok").value(false))
                .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.containsString("förbjuder inbäddning")));
        assertThat(store.fontFiles()).isEmpty();
    }

    @Test
    void openTypeCffIsRejected() throws Exception {
        byte[] otf = "OTTO-not-really-a-font".getBytes(StandardCharsets.ISO_8859_1);
        mvc.perform(admin(multipart("/admin/api/fonts").file(new MockMultipartFile("files", "x.otf", null, otf))))
                .andExpect(jsonPath("$[0].ok").value(false))
                .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.containsString("OpenType/CFF")));
    }

    @Test
    void exportAndImportMoveTheWholeSet() throws Exception {
        mvc.perform(admin(multipart("/admin/api/fonts").file(font(TEST_FONT)))).andExpect(status().isOk());
        mvc.perform(admin(put("/admin/api/aliases")).contentType(MediaType.APPLICATION_JSON).content("{\"Garamond\": \"Filkonverter Test\"}"));

        byte[] zip = mvc.perform(get("/admin/api/export").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zipEntries(zip)).containsExactlyInAnyOrder("fonts/" + TEST_FONT, AssetStore.ALIASES_FILE);

        cleanUp();
        assertThat(fonts.hasFamily("Filkonverter Test")).isFalse();

        mvc.perform(admin(multipart("/admin/api/import").file(new MockMultipartFile("file", "set.zip", null, zip))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fonts").value(1))
                .andExpect(jsonPath("$.aliases").value(1));
        assertThat(fonts.hasFamily("Filkonverter Test")).isTrue();
        assertThat(fonts.aliases()).containsEntry("Garamond", "Filkonverter Test");
    }

    @Test
    void fontAddedByAnotherPodIsPickedUp() throws Exception {
        Files.write(store.fontsDir().resolve(TEST_FONT), TestPdfs.fixture(TEST_FONT));
        assertThat(fonts.hasFamily("Filkonverter Test")).isFalse();

        assertThat(reloader.reloadIfChanged()).isTrue();

        assertThat(fonts.hasFamily("Filkonverter Test")).isTrue();
    }

    @Test
    void checkExplainsFontsAndVerdict() throws Exception {
        mvc.perform(admin(multipart("/admin/api/check").file(fixture("beslut-calibri.docx"))).param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("office"))
                .andExpect(jsonPath("$.compliant").value(true))
                .andExpect(jsonPath("$.requestedFonts[?(@.name == 'Calibri')].status").value("ersätts"))
                .andExpect(jsonPath("$.requestedFonts[?(@.name == 'Calibri')].replacement").value("Carlito"))
                .andExpect(jsonPath("$.outputFonts[?(@.name == 'Carlito-Regular')].embedded").value(true))
                .andExpect(jsonPath("$.visual.differingPercent").value(org.hamcrest.Matchers.lessThan(0.5)))
                .andExpect(jsonPath("$.visual.pageCountDiffers").value(false))
                .andExpect(jsonPath("$.pdf").isNotEmpty());
    }

    @Test
    void builtInAliasesWithSpacesSurviveConfigBinding() {
        assertThat(fonts.aliases())
                .containsEntry("Times New Roman", "Liberation Serif")
                .containsEntry("Courier New", "Liberation Mono")
                .containsEntry("sans-serif", "Liberation Sans");
    }

    @Test
    void checkListsFontsSetInWordStyles() throws Exception {
        mvc.perform(admin(multipart("/admin/api/fonts").file(font(TEST_FONT)))).andExpect(status().isOk());

        mvc.perform(admin(multipart("/admin/api/check").file(fixture("egen-font.docx"))).param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliant").value(true))
                .andExpect(jsonPath("$.requestedFonts[?(@.name == 'Filkonverter Test')].status").value("installerad"))
                // Fonter som bara står i Words fonttabell, utan att användas, ska inte rapporteras som saknade.
                .andExpect(jsonPath("$.requestedFonts[?(@.name == 'Symbol')]").isEmpty())
                .andExpect(jsonPath("$.requestedFonts[?(@.status == 'saknas')]").isEmpty());
    }

    @Test
    void checkFlagsVisibleChanges() throws Exception {
        // Samma bild mot två bakgrunder läggs mot vitt på sida 2: godkänd, men synligt ändrad.
        mvc.perform(admin(multipart("/admin/api/check")
                        .file(new MockMultipartFile("files", "kund.pdf", "application/pdf", TestPdfs.sameAlphaImageOnTwoBackgrounds())))
                        .param("pdfa", "PDF/A-1b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliant").value(true))
                .andExpect(jsonPath("$.visual.worstPage").value(2))
                .andExpect(jsonPath("$.visual.differingPercent").value(org.hamcrest.Matchers.greaterThan(0.5)));
    }

    @Test
    void checkShowsWhyAFileIsRejected() throws Exception {
        mvc.perform(admin(multipart("/admin/api/check")
                        .file(new MockMultipartFile("files", "kund.pdf", "application/pdf", TestPdfs.withTransparency())))
                        .param("pdfa", "PDF/A-1b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converted").value(false))
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.containsString("Transparens")));
    }

    @Test
    void statsShowConversionsRejectionsAndRecentFailuresWithTraceId() throws Exception {
        mvc.perform(multipart("/forms/pdfengines/convert")
                        .file(new MockMultipartFile("files", "kund.pdf", "application/pdf", TestPdfs.withTransparency()))
                        .param("pdfa", "PDF/A-1b").header("Gotenberg-Trace", "statistik-1"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(multipart("/forms/pdfengines/convert")
                        .file(new MockMultipartFile("files", "kund.pdf", "application/pdf", TestPdfs.untaggedWithAlphaImage()))
                        .param("pdfa", "PDF/A-1b"))
                .andExpect(status().isOk());

        mvc.perform(get("/admin/api/stats").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[?(@.route == 'pdfengines-convert')].outcomes.ok").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.rejections.TRANSPARENCY").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.recentFailures[?(@.traceId == 'statistik-1')].reason").value(org.hamcrest.Matchers.hasItem("TRANSPARENCY")));

        assertThat(meters.get("converter.rejections").tag("reason", "TRANSPARENCY").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("converter.conversions").tag("route", "pdfengines-convert").tag("outcome", "ok").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    @Test
    void adminPageIsServed() throws Exception {
        mvc.perform(get("/admin/index.html").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Filkonverterare")));
    }

    private List<String> officeFonts(String fixture) throws Exception {
        byte[] pdf = mvc.perform(multipart("/forms/libreoffice/convert").file(fixture(fixture)).param("pdfa", "PDF/A-1a"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return fontNames(pdf);
    }

    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B admin(B request) {
        return request.header("Authorization", AUTH).header("X-Requested-With", "test");
    }

    private static MockMultipartFile font(String name) {
        return new MockMultipartFile("files", name, null, TestPdfs.fixture(name));
    }

    private static MockMultipartFile fixture(String name) {
        return new MockMultipartFile("files", name, null, TestPdfs.fixture(name));
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

    private static List<String> zipEntries(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}

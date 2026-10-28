package se.fileconverter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Mätvärden över vad klienterna skickar: fält, huvuden, filtyper och routes som inte finns. Underlaget
 * för att se före och efter bytet från Gotenberg om något används som tjänsten inte stöder.
 */
@SpringBootTest(properties = "converter.office.enabled=false")
@AutoConfigureMockMvc
class RequestUsageTest {

    @TempDir
    static Path assets;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("converter.assets.dir", () -> assets.toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry registry;

    @Test
    void fieldsHeadersAndFileTypesAreCountedPerRoute() throws Exception {
        double ignored = count("converter.request.fields", "route", "chromium-html", "field", "waitDelay", "handling", "ignoreras");
        double unknown = count("converter.request.fields", "route", "chromium-html", "field", "okant", "handling", "ignoreras");
        double trace = count("converter.request.fields", "route", "chromium-html", "field", "Gotenberg-Trace", "handling", "stods");
        double pdfa = count("converter.request.fields", "route", "chromium-html", "field", "pdfa", "handling", "stods");
        double html = count("converter.request.files", "route", "chromium-html", "extension", "html");
        double png = count("converter.request.files", "route", "chromium-html", "extension", "png");

        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).file(new MockMultipartFile("files", "logo.png", null, new byte[0]))
                        .param("waitDelay", "2s").param("klientensEgetFalt", "x").param("pdfa", "PDF/A-1b")
                        .header("Gotenberg-Trace", "arende-1"))
                .andExpect(status().isOk());

        assertThat(count("converter.request.fields", "route", "chromium-html", "field", "waitDelay", "handling", "ignoreras")).isEqualTo(ignored + 1);
        assertThat(count("converter.request.fields", "route", "chromium-html", "field", "okant", "handling", "ignoreras")).isEqualTo(unknown + 1);
        assertThat(count("converter.request.fields", "route", "chromium-html", "field", "Gotenberg-Trace", "handling", "stods")).isEqualTo(trace + 1);
        assertThat(count("converter.request.fields", "route", "chromium-html", "field", "pdfa", "handling", "stods")).isEqualTo(pdfa + 1);
        assertThat(count("converter.request.files", "route", "chromium-html", "extension", "html")).isEqualTo(html + 1);
        assertThat(count("converter.request.files", "route", "chromium-html", "extension", "png")).isEqualTo(png + 1);
    }

    @Test
    void rejectedFieldsAreCountedEvenThoughTheRequestStops() throws Exception {
        double scale = count("converter.request.fields", "route", "chromium-html", "field", "scale", "handling", "avvisas");
        double paper = count("converter.request.fields", "route", "chromium-html", "field", "paperWidth", "handling", "stods");
        double webhook = count("converter.request.fields", "route", "chromium-html", "field", "Gotenberg-Webhook-Url", "handling", "avvisas");

        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).param("scale", "2").param("paperWidth", "8.27"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).header("gotenberg-webhook-url", "http://klient"))
                .andExpect(status().isBadRequest());

        assertThat(count("converter.request.fields", "route", "chromium-html", "field", "scale", "handling", "avvisas")).isEqualTo(scale + 1);
        assertThat(count("converter.request.fields", "route", "chromium-html", "field", "paperWidth", "handling", "stods")).isEqualTo(paper + 1);
        assertThat(count("converter.request.fields", "route", "chromium-html", "field", "Gotenberg-Webhook-Url", "handling", "avvisas"))
                .isEqualTo(webhook + 1);
    }

    /** Gotenberg-fält som skulle ge ett annat resultat än klienten väntar sig avvisas i stället för att ignoreras. */
    @Test
    void encryptionWatermarksEmbedsAndAllWebhookHeadersAreRejected() throws Exception {
        double password = count("converter.request.fields", "route", "chromium-html", "field", "userPassword", "handling", "avvisas");
        double embeds = count("converter.request.fields", "route", "chromium-html", "field", "embeds", "handling", "avvisas");

        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).param("userPassword", "hemligt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("okrypterad")));
        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).param("ownerPassword", "hemligt"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).param("watermarkSource", "text"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).param("stampExpression", "Utkast"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/forms/chromium/convert/html").file(html())
                        .file(new MockMultipartFile("embeds", "faktura.xml", "application/xml", "<x/>".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest());
        for (String header : new String[] {"Gotenberg-Webhook-Method", "Gotenberg-Webhook-Extra-Http-Headers", "Gotenberg-Webhook-Nagot-Nytt"}) {
            mvc.perform(multipart("/forms/chromium/convert/html").file(html()).header(header, "x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("UNSUPPORTED_OPTION"));
        }
        // Tomma lösenord betyder ingen kryptering, också i Gotenberg.
        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).param("userPassword", ""))
                .andExpect(status().isOk());

        assertThat(count("converter.request.fields", "route", "chromium-html", "field", "userPassword", "handling", "avvisas"))
                .isEqualTo(password + 1);
        assertThat(count("converter.request.fields", "route", "chromium-html", "field", "embeds", "handling", "avvisas"))
                .isEqualTo(embeds + 1);
    }

    @Test
    void unsupportedPdfaValuesDoNotBecomeNewSeries() throws Exception {
        double invalid = count("converter.conversions", "route", "chromium-html", "pdfa", "ogiltig", "outcome", "avvisad");

        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).param("pdfa", "PDF/A-2b"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).param("pdfa", "nagot-helt-annat-1234"))
                .andExpect(status().isBadRequest());

        assertThat(count("converter.conversions", "route", "chromium-html", "pdfa", "ogiltig", "outcome", "avvisad")).isEqualTo(invalid + 2);
        assertThat(registry.find("converter.conversions").tag("pdfa", "PDF/A-2b").counter()).isNull();
        assertThat(registry.find("converter.conversions").tag("pdfa", "nagot-helt-annat-1234").counter()).isNull();
    }

    @Test
    void gotenbergRoutesThatDoNotExistHereAreCounted() throws Exception {
        double url = count("converter.unknown.routes", "route", "/forms/chromium/convert/url", "method", "POST");
        double split = count("converter.unknown.routes", "route", "/forms/pdfengines/split", "method", "POST");
        double other = count("converter.unknown.routes", "route", "okand", "method", "POST");

        mvc.perform(multipart("/forms/chromium/convert/url").param("url", "https://example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("UNSUPPORTED_ROUTE"));
        mvc.perform(post("/forms/pdfengines/split")).andExpect(status().isNotFound());
        mvc.perform(post("/forms/nagot/annat")).andExpect(status().isNotFound());

        assertThat(count("converter.unknown.routes", "route", "/forms/chromium/convert/url", "method", "POST")).isEqualTo(url + 1);
        assertThat(count("converter.unknown.routes", "route", "/forms/pdfengines/split", "method", "POST")).isEqualTo(split + 1);
        assertThat(count("converter.unknown.routes", "route", "okand", "method", "POST")).isEqualTo(other + 1);
    }

    @Test
    void requestsThatAreNotMultipartAreCountedAsRejected() throws Exception {
        double rejected = count("converter.conversions", "route", "pdfengines-merge", "pdfa", "ingen", "outcome", "avvisad");
        double reason = count("converter.rejections", "reason", "BAD_REQUEST");

        mvc.perform(post("/forms/pdfengines/merge").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(count("converter.conversions", "route", "pdfengines-merge", "pdfa", "ingen", "outcome", "avvisad")).isEqualTo(rejected + 1);
        assertThat(count("converter.rejections", "reason", "BAD_REQUEST")).isEqualTo(reason + 1);
    }

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static MockMultipartFile html() {
        return new MockMultipartFile("files", "index.html", "text/html",
                "<html><body><p>x</p></body></html>".getBytes(StandardCharsets.UTF_8));
    }
}

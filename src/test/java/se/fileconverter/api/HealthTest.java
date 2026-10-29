package se.fileconverter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.fileconverter.office.LibreOfficeHealth;
import se.fileconverter.office.OfficeConverter;

/**
 * Hälsokontroller och mätvärden. Actuatorn ligger här på samma port som API:t, så att MockMvc når den;
 * i drift ligger den på 9090.
 */
@SpringBootTest(properties = {"converter.office.enabled=false", "management.server.port=3000"})
@AutoConfigureMockMvc
class HealthTest {

    @TempDir
    static Path assets;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("converter.assets.dir", () -> assets.toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void healthHasGotenbergsForm() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("up"))
                .andExpect(jsonPath("$.details.libreoffice.status").value("disabled"))
                .andExpect(jsonPath("$.details.libreoffice.timestamp").isString());
    }

    @Test
    void versionIsPlainText() throws Exception {
        mvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())));
    }

    @Test
    void probesAreSeparate() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.libreoffice").doesNotExist());
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.libreoffice.status").value("UP"));
    }

    /** En LibreOffice som är nere tar podden ur trafik (readiness) men startar inte om den (liveness). */
    @Test
    void libreOfficeDownIsNotReady() {
        OfficeConverter office = mock(OfficeConverter.class);
        when(office.isEnabled()).thenReturn(true);
        when(office.isRunning()).thenReturn(false);
        when(office.version()).thenReturn("LibreOffice 26.2");

        assertThat(new LibreOfficeHealth(office).health().getStatus()).isEqualTo(Status.DOWN);
        when(office.isRunning()).thenReturn(true);
        assertThat(new LibreOfficeHealth(office).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void metricsForLoadVersionsAndLongConversions() throws Exception {
        mvc.perform(multipart("/forms/chromium/convert/html").file(new MockMultipartFile("files", "index.html", "text/html",
                "<html><body><p>x</p></body></html>".getBytes(StandardCharsets.UTF_8)))).andExpect(status().isOk());

        String metrics = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(metrics).contains("converter_build_info{", "libreoffice=\"disabled\"", "converter_inflight{",
                "converter_concurrency_limit{", "converter_queue_waiting{", "converter_queue_wait_seconds_bucket{",
                "converter_libreoffice_up{", "converter_font_aliases_missing{", "converter_assets_last_reload_timestamp_seconds{");
        // Histogrammet räcker förbi 30 s, som är Micrometers standard.
        assertThat(metrics.lines().filter(line -> line.startsWith("converter_conversion_duration_seconds_bucket{")))
                .anyMatch(line -> line.contains("le=\"240.0\""))
                .anyMatch(line -> line.contains("le=\"120.0\""));
    }
}

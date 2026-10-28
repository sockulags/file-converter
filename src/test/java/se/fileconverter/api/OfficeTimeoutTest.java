package se.fileconverter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
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

/** En konvertering som inte hinner klart är inte filens fel: 503 som i Gotenberg, inte 422. */
@SpringBootTest(properties = {"converter.office.pool-size=1", "converter.office.task-timeout=1ms"})
@AutoConfigureMockMvc
@EnabledIf("se.fileconverter.api.ConversionControllerTest#libreOfficeInstalled")
class OfficeTimeoutTest {

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
    void timeoutIsServiceUnavailableAndCountedAsAnError() throws Exception {
        mvc.perform(multipart("/forms/libreoffice/convert")
                        .file(new MockMultipartFile("files", "beslut.docx", null, TestPdfs.fixture("beslut.docx"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("LIBREOFFICE_TIMEOUT"));

        Counter errors = registry.find("converter.conversions").tags("route", "libreoffice", "outcome", "fel").counter();
        assertThat(errors).isNotNull();
        assertThat(errors.count()).isEqualTo(1);
    }
}

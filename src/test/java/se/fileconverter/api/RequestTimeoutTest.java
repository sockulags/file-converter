package se.fileconverter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/** Hela anropet har en tidsgräns. Överskrids den blir svaret 503 TIMEOUT och räknas som fel. */
@SpringBootTest(properties = {"converter.office.enabled=false", "converter.limits.request-timeout=1ms"})
@AutoConfigureMockMvc
class RequestTimeoutTest {

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
    void conversionOverTheLimitIs503Timeout() throws Exception {
        mvc.perform(multipart("/forms/chromium/convert/html").file(html()).header("Gotenberg-Trace", "arende-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Gotenberg-Trace", "arende-1"))
                .andExpect(jsonPath("$.reason").value("TIMEOUT"))
                .andExpect(jsonPath("$.detail").value("Konverteringen blev inte klar inom 1 ms."))
                .andExpect(jsonPath("$.traceId").value("arende-1"));

        assertThat(count("converter.timeouts", "stage", "anrop")).isEqualTo(1);
        assertThat(count("converter.rejections", "reason", "TIMEOUT")).isEqualTo(1);
        assertThat(count("converter.conversions", "route", "chromium-html", "outcome", "fel")).isEqualTo(1);
    }

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    static MockMultipartFile html() {
        return new MockMultipartFile("files", "index.html", "text/html",
                "<html><body><p>x</p></body></html>".getBytes(StandardCharsets.UTF_8));
    }
}

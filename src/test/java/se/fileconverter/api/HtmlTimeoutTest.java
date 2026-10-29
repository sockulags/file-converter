package se.fileconverter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Renderingen av en mall har en egen tidsgräns, kortare än hela anropets. */
@SpringBootTest(properties = {"converter.office.enabled=false", "converter.limits.html-timeout=1ms"})
@AutoConfigureMockMvc
class HtmlTimeoutTest {

    @TempDir
    static Path assets;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("converter.assets.dir", () -> assets.toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void renderingOverTheLimitIs503Timeout() throws Exception {
        // En mall som tar många millisekunder att rendera även i en uppvärmd JVM; <p>x</p> kan bli klar på under 1 ms.
        StringBuilder rows = new StringBuilder("<html><body><table>");
        for (int i = 0; i < 2000; i++) {
            rows.append("<tr><td>Rad ").append(i).append("</td><td>Belopp ").append(i * 7).append(" kr</td></tr>");
        }
        mvc.perform(multipart("/forms/chromium/convert/html").file(new MockMultipartFile("files", "index.html", "text/html",
                        rows.append("</table></body></html>").toString().getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("TIMEOUT"))
                .andExpect(jsonPath("$.detail").value("HTML-renderingen blev inte klar inom 1 ms."));
    }
}

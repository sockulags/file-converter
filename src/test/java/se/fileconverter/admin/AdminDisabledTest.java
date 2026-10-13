package se.fileconverter.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Utan lösenord ska administrationen inte finnas, hur adressen än skrivs. */
@SpringBootTest(properties = {"converter.office.enabled=false", "converter.admin.password="})
@AutoConfigureMockMvc
class AdminDisabledTest {

    @TempDir
    static Path assets;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("converter.assets.dir", () -> assets.toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void adminDoesNotExistWithoutPassword() throws Exception {
        for (String path : new String[] {"/admin/", "/admin/index.html", "/admin/api/fonts", "/%61dmin/api/fonts", "/admin;x/api/fonts"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}

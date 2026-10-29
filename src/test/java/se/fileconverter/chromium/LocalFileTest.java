package se.fileconverter.chromium;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bara filer som skickats med i anropet får besvaras, oavsett hur adressen skrivs. */
class LocalFileTest {

    @TempDir
    Path dir;

    @Test
    void onlyFilesDirectlyInTheWorkspaceAreServed() throws IOException {
        Files.writeString(dir.resolve("index.html"), "x");
        Files.writeString(dir.resolve("logga.png"), "x");
        Files.writeString(dir.resolve("med mellanslag.css"), "x");
        Path root = dir.toRealPath();
        String origin = ChromiumConverter.ORIGIN;

        assertThat(ChromiumConverter.localFile(origin + "/", root)).contains(root.resolve("index.html"));
        assertThat(ChromiumConverter.localFile(origin + "/logga.png?v=2", root)).contains(root.resolve("logga.png"));
        assertThat(ChromiumConverter.localFile(origin + "/med%20mellanslag.css", root)).contains(root.resolve("med mellanslag.css"));
        assertThat(ChromiumConverter.localFile(origin + "/saknas.png", root)).isEmpty();
        assertThat(ChromiumConverter.localFile(origin + "/../etc/hostname", root)).isEmpty();
        assertThat(ChromiumConverter.localFile(origin + "/%2e%2e%2fetc%2fhostname", root)).isEmpty();
        assertThat(ChromiumConverter.localFile(origin + "/.hemlig", root)).isEmpty();
        assertThat(ChromiumConverter.localFile("file://" + root.resolve("index.html"), root)).isEmpty();
        assertThat(ChromiumConverter.localFile("http://169.254.169.254/latest/meta-data", root)).isEmpty();
        assertThat(ChromiumConverter.localFile(origin + ".example.com/index.html", root)).isEmpty();
    }
}

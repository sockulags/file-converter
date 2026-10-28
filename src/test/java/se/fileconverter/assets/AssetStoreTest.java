package se.fileconverter.assets;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.fileconverter.config.ConverterProperties;

class AssetStoreTest {

    @TempDir
    Path dir;

    @Test
    void startsWithoutUploadedFontsWhenTheVolumeIsNotWritable() throws Exception {
        // En fil där katalogen ska ligga ger samma fel som en volym utan skrivrätt.
        Path blocked = Files.writeString(dir.resolve("data"), "");
        AssetStore store = new AssetStore(new ConverterProperties(null, new ConverterProperties.Fonts(List.of(), Map.of()),
                null, new ConverterProperties.Assets(blocked.toString(), Duration.ofSeconds(30), Duration.ZERO), null, null));

        assertThat(store.fontFiles()).isEmpty();
        assertThat(store.aliases()).isEmpty();
        assertThat(store.fingerprint()).isNotNull();
    }
}

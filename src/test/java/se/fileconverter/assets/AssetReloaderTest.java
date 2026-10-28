package se.fileconverter.assets;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.fileconverter.chromium.ChromiumConverter;
import se.fileconverter.config.ChromiumProperties;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.html.FontRegistry;
import se.fileconverter.office.OfficeConverter;

class AssetReloaderTest {

    @TempDir
    Path assets;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private AssetReloader reloader;

    private ConverterProperties properties(Duration jitter) throws Exception {
        Path fixtures = Paths.get(AssetReloaderTest.class.getResource("/fixtures/FilkonverterTest-Regular.ttf").toURI()).getParent();
        return new ConverterProperties(
                new ConverterProperties.Office(false, "", 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, null),
                new ConverterProperties.Fonts(List.of(fixtures.toString()), Map.of("Garamond", "Filkonverter Test", "Arial", "Finns Inte")),
                null, new ConverterProperties.Assets(assets.toString(), Duration.ofHours(1), jitter), null, null);
    }

    /** Avstängd Chromium: omstarten vid omladdning gör då ingenting. */
    private static ChromiumConverter chromium(ConverterProperties properties, AssetStore store, FontRegistry fonts) {
        return new ChromiumConverter(new ChromiumProperties(false, "", false, 1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1), properties, store, fonts);
    }

    @AfterEach
    void stop() {
        if (reloader != null) {
            reloader.destroy();
        }
    }

    @Test
    void aliasesWithoutAnInstalledTargetAreReported() throws Exception {
        ConverterProperties properties = properties(Duration.ZERO);
        AssetStore store = new AssetStore(properties);
        FontRegistry fonts = new FontRegistry(properties, store);
        FontAliasCheck check = new FontAliasCheck(fonts, registry);

        assertThat(check.missing()).containsOnlyKeys("Arial");
        assertThat(registry.get("converter.font.aliases.missing").gauge().value()).isEqualTo(1);

        reloader = new AssetReloader(store, fonts, new OfficeConverter(properties, store, registry), chromium(properties, store, fonts),
                check, properties, registry);
        store.saveAliases(Map.of("Times", "Saknas Ocksa"));
        assertThat(reloader.reloadIfChanged()).isTrue();

        assertThat(check.missing()).containsOnlyKeys("Arial", "Times");
        assertThat(registry.get("converter.assets.reloads").tag("result", "ok").counter().count()).isEqualTo(1);
    }

    /** En ändring från en annan podd laddas in efter en slumpad väntan, inte direkt i alla poddar samtidigt. */
    @Test
    void changesFromOtherPodsAreReloadedAfterARandomDelay() throws Exception {
        ConverterProperties properties = properties(Duration.ofHours(1));
        AssetStore store = new AssetStore(properties);
        FontRegistry fonts = new FontRegistry(properties, store);
        reloader = new AssetReloader(store, fonts, new OfficeConverter(properties, store, registry),
                chromium(properties, store, fonts), new FontAliasCheck(fonts, registry), properties, registry);

        store.saveAliases(Map.of("Times", "Filkonverter Test"));
        reloader.poll();

        assertThat(fonts.aliases()).doesNotContainKey("Times");
        assertThat(registry.find("converter.assets.reloads").counter()).isNull();
        for (int i = 0; i < 100; i++) {
            assertThat(reloader.delay()).isBetween(0L, Duration.ofHours(1).toMillis());
        }
        // Podden där ändringen gjordes laddar om direkt.
        assertThat(reloader.reloadIfChanged()).isTrue();
        assertThat(fonts.aliases()).containsEntry("Times", "Filkonverter Test");
    }
}

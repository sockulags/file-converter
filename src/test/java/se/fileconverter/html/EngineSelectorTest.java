package se.fileconverter.html;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import se.fileconverter.html.EngineSelector.Engine;

class EngineSelectorTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "<p>Brev</p>|openhtmltopdf|standard",
            "<div style='display: flex'>x</div>|chromium|flexbox",
            "<style>.rad { display:inline-grid }</style>|chromium|grid",
            "<script>document.title = 'x'</script>|chromium|script",
            "<script type='application/ld+json'>{}</script>|openhtmltopdf|standard",
            "<button onclick='x()'>x</button>|chromium|script",
            "<svg><circle r='1'/></svg>|chromium|svg",
            "<img src='logga.svg'>|chromium|svg",
            "<canvas></canvas>|chromium|canvas",
            "<style>p { text-transform: uppercase; opacity: 1 }</style>|openhtmltopdf|standard",
            "<style>p { opacity: .5 }</style>|chromium|css3",
            "<style>/* display: flex */ p { color: red }</style>|openhtmltopdf|standard",
            "<style>:root { --farg: red }</style><p style='color: var(--farg)'>x</p>|chromium|css3",
    })
    void signalsInTheTemplatePickTheEngine(String html, String engine, String reason) throws IOException {
        Files.writeString(dir.resolve("index.html"), "<html><body>" + html + "</body></html>");

        EngineSelector.Selection selection = EngineSelector.select(dir, Map.<String, String>of()::get);

        assertThat(selection.engine().label()).isEqualTo(engine);
        assertThat(selection.reason()).isEqualTo(reason);
    }

    @Test
    void stylesheetsAndHeaderCount() throws IOException {
        Files.writeString(dir.resolve("index.html"), "<html><body><p>x</p></body></html>");
        Files.writeString(dir.resolve("stil.css"), ".kolumner { display: grid; }");
        assertThat(EngineSelector.select(dir, Map.<String, String>of()::get).reason()).isEqualTo("grid");

        Files.delete(dir.resolve("stil.css"));
        Files.writeString(dir.resolve("footer.html"), "<html><body><script>1</script></body></html>");
        assertThat(EngineSelector.select(dir, Map.<String, String>of()::get).reason()).isEqualTo("script");
    }

    @Test
    void browserFieldsAndExplicitChoiceWin() throws IOException {
        Files.writeString(dir.resolve("index.html"), "<html><body><div style='display:flex'>x</div></body></html>");

        assertThat(EngineSelector.select(dir, Map.of("converterEngine", "openhtmltopdf")::get))
                .isEqualTo(new EngineSelector.Selection(Engine.OPENHTMLTOPDF, "vald"));
        Files.writeString(dir.resolve("index.html"), "<html><body><p>x</p></body></html>");
        assertThat(EngineSelector.select(dir, Map.of("waitDelay", "1s")::get).reason()).isEqualTo("falt");
        assertThat(EngineSelector.select(dir, Map.of("scale", "1.0")::get).engine()).isEqualTo(Engine.OPENHTMLTOPDF);
        assertThat(EngineSelector.select(dir, Map.of("emulatedMediaType", "screen")::get).engine()).isEqualTo(Engine.CHROMIUM);
        assertThatThrownBy(() -> EngineSelector.select(dir, Map.of("converterEngine", "wkhtmltopdf")::get))
                .hasMessageContaining("chromium eller openhtmltopdf");
    }
}

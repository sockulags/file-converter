package se.fileconverter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import se.fileconverter.config.ConverterProperties.Logging.FileNames;

/** Kundernas filnamn kan innehålla personuppgifter. Som standard loggas och visas bara filändelsen. */
class ConversionTelemetryTest {

    private static ConversionRecord failed() {
        ConversionRecord record = new ConversionRecord().start("libreoffice", null,
                List.of(new MockMultipartFile("files", "Beslut Anna Andersson.docx", null, new byte[2048])));
        record.rejected("LIBREOFFICE_FAILED", "LibreOffice kunde inte konvertera 'Beslut Anna Andersson.docx'.",
                List.of("Beslut Anna Andersson.docx: trasig"));
        return record;
    }

    @Test
    void onlyTheExtensionByDefault() {
        ConversionTelemetry telemetry = new ConversionTelemetry(new SimpleMeterRegistry());
        ConversionRecord record = failed();
        telemetry.record(record, Duration.ofMillis(10));

        assertThat(telemetry.files(record)).containsExactly("*.docx (2 kB)");
        ConversionTelemetry.Failure failure = telemetry.snapshot().recentFailures().getFirst();
        assertThat(failure.files()).containsExactly("*.docx (2 kB)");
        assertThat(failure.message()).isEqualTo("LibreOffice kunde inte konvertera '*.docx'.");
        assertThat(failure.details()).containsExactly("*.docx: trasig");
    }

    @Test
    void noneAndFull() {
        ConversionRecord record = failed();
        assertThat(new ConversionTelemetry(new SimpleMeterRegistry(), FileNames.NONE).files(record)).containsExactly("fil (2 kB)");
        ConversionTelemetry full = new ConversionTelemetry(new SimpleMeterRegistry(), FileNames.FULL);
        full.record(record, Duration.ofMillis(10));
        assertThat(full.files(record)).containsExactly("Beslut Anna Andersson.docx (2 kB)");
        assertThat(full.snapshot().recentFailures().getFirst().message()).contains("Beslut Anna Andersson.docx");
    }
}

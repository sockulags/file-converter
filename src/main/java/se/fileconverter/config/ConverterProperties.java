package se.fileconverter.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("converter")
public record ConverterProperties(Office office, Fonts fonts, Pdfa pdfa) {

    /**
     * LibreOffice-poolen. Varje process hanterar en konvertering åt gången.
     */
    public record Office(
            boolean enabled,
            String home,
            int poolSize,
            Duration taskTimeout,
            Duration queueTimeout,
            int maxTasksPerProcess) {
    }

    /**
     * Fonter som HTML-renderaren får använda. Allt annat avvisas, så att inget byts ut i tysthet.
     *
     * @param dirs    kataloger som genomsöks efter .ttf-filer
     * @param aliases familjenamn i mallar mot installerad familj, t.ex. Arial -> Liberation Sans
     */
    public record Fonts(List<String> dirs, Map<String, String> aliases) {
    }

    /**
     * @param strict avvisa (422) när resultatet inte klarar veraPDF i stället för att returnera det
     */
    public record Pdfa(boolean strict) {
    }
}

package se.fileconverter.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("converter")
public record ConverterProperties(Office office, Fonts fonts, Pdfa pdfa, Assets assets, Admin admin, Limits limits,
        Logging logging) {

    @ConstructorBinding
    public ConverterProperties {
    }

    /** Utan loggningsinställningar, för tester. */
    public ConverterProperties(Office office, Fonts fonts, Pdfa pdfa, Assets assets, Admin admin, Limits limits) {
        this(office, fonts, pdfa, assets, admin, limits, null);
    }

    /** Hur filnamn skrivs i loggar och i administrationens lista över fel. */
    public Logging.FileNames fileNames() {
        return logging == null || logging.fileNames() == null ? Logging.FileNames.EXTENSION : logging.fileNames();
    }

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

    /**
     * Resurser som administreras i drift (uppladdade fonter, alias).
     *
     * @param dir          katalog, i Kubernetes en delad volym som alla poddar monterar
     * @param pollInterval hur ofta katalogen kontrolleras efter ändringar från andra poddar
     * @param reloadJitter  längsta slumpade väntan innan en ändring från en annan podd laddas in, så att
     *                      poddarna inte startar om LibreOffice samtidigt
     */
    public record Assets(String dir, Duration pollInterval, Duration reloadJitter) {
    }

    /**
     * Tidsgränser är 0 för att stänga av dem.
     *
     * @param maxConcurrent      samtidiga konverteringar per instans, 0 = antal processorer
     * @param queueTimeout       hur länge ett anrop får vänta på en plats innan det får 503
     * @param maxQueued          hur många anrop som får vänta på en plats, 0 = obegränsat. Fler får 503 direkt
     * @param requestTimeout     hela konverteringen, från att anropet fått en plats
     * @param htmlTimeout        renderingen av en HTML-mall
     * @param postProcessTimeout efterbehandlingen av en PDF (metadata, formulär, sidurval)
     * @param pdfaTimeout        PDF/A-normaliseringen och valideringen med veraPDF av en PDF
     */
    public record Limits(int maxConcurrent, Duration queueTimeout, int maxQueued, Duration requestTimeout,
            Duration htmlTimeout, Duration postProcessTimeout, Duration pdfaTimeout) {

        @ConstructorBinding
        public Limits {
        }

        public Limits(int maxConcurrent, Duration queueTimeout) {
            this(maxConcurrent, queueTimeout, 0, null, null, null, null);
        }
    }

    /**
     * @param fileNames hur kundernas filnamn skrivs i loggar och i administrationen. Filnamn kan
     *                  innehålla personuppgifter, så standard är bara filändelsen.
     */
    public record Logging(FileNames fileNames) {

        public enum FileNames {
            /** Inget om filen utom storleken. */
            NONE,
            /** Filändelsen, t.ex. {@code *.docx}. */
            EXTENSION,
            /** Hela filnamnet. */
            FULL;

            public String describe(String fileName) {
                return switch (this) {
                    case FULL -> String.valueOf(fileName);
                    case NONE -> "fil";
                    case EXTENSION -> {
                        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
                        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
                        yield extension.matches("[a-z0-9]{1,10}") ? "*." + extension : "fil";
                    }
                };
            }
        }
    }

    /**
     * @param password lösenord för /admin. Tomt stänger av administrationen helt.
     */
    public record Admin(String password) {
        public boolean enabled() {
            return password != null && !password.isBlank();
        }
    }
}

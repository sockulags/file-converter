package se.fileconverter.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import se.fileconverter.office.OfficeConverter;

/**
 * Versioner i drift: tjänsten, Java och LibreOffice. Loggas vid start och exponeras som
 * {@code converter_build_info{version,java,libreoffice} 1}, så att det syns vilken LibreOffice-version
 * en förändring i resultaten sammanfaller med.
 */
@Component
public class BuildInfo {

    private static final Logger log = LoggerFactory.getLogger(BuildInfo.class);

    private final String version;
    private final String java;
    private final OfficeConverter office;

    public BuildInfo(ObjectProvider<BuildProperties> build, OfficeConverter office, MeterRegistry registry) {
        BuildProperties properties = build.getIfAvailable();
        this.version = properties != null ? properties.getVersion() : "dev";
        this.java = Runtime.version().toString();
        this.office = office;
        Gauge.builder("converter.build.info", () -> 1)
                .description("Versioner av tjänsten, Java och LibreOffice")
                .tag("version", version).tag("java", java).tag("libreoffice", libreoffice())
                .register(registry);
        log.info("file-converter {} på Java {} med {}", version, java, libreoffice());
    }

    public String version() {
        return version;
    }

    public String java() {
        return java;
    }

    /** "LibreOffice 26.2.0.3 …", "disabled" eller "okand". */
    public String libreoffice() {
        return office.isEnabled() ? office.version() : "disabled";
    }
}

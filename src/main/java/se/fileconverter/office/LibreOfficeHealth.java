package se.fileconverter.office;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * LibreOffice-poolen i actuatorns hälsokontroll. Ingår i readiness-gruppen men inte i liveness:
 * en podd vars LibreOffice startas om eller inte kommer upp tas ur trafik, men startas inte om.
 */
@Component("libreoffice")
public class LibreOfficeHealth implements HealthIndicator {

    private final OfficeConverter office;

    public LibreOfficeHealth(OfficeConverter office) {
        this.office = office;
    }

    @Override
    public Health health() {
        if (!office.isEnabled()) {
            return Health.up().withDetail("libreoffice", "disabled").build();
        }
        return (office.isRunning() ? Health.up() : Health.down()).withDetail("version", office.version()).build();
    }
}

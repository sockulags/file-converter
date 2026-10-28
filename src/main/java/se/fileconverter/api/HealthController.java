package se.fileconverter.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import se.fileconverter.observability.BuildInfo;
import se.fileconverter.office.OfficeConverter;

/**
 * Gotenbergs {@code /health} och {@code /version} på API-porten.
 *
 * <p>{@code /health} har samma form som i Gotenberg 8, med versionen som tillägg:
 * {@code {"status":"up","details":{"libreoffice":{"status":"up","timestamp":"…","version":"…"}}}}.
 * Den svarar 503 när LibreOffice inte är igång. Kubernetes använder i stället actuatorns
 * {@code /actuator/health/liveness} och {@code /actuator/health/readiness} på port 9090, där
 * liveness inte beror på LibreOffice.
 */
@RestController
public class HealthController {

    private final OfficeConverter office;
    private final BuildInfo build;

    public HealthController(OfficeConverter office, BuildInfo build) {
        this.office = office;
        this.build = build;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        String libreoffice = !office.isEnabled() ? "disabled" : office.isRunning() ? "up" : "down";
        boolean up = !"down".equals(libreoffice);
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("status", libreoffice);
        module.put("timestamp", Instant.now().toString());
        module.put("version", build.libreoffice());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "up" : "down");
        body.put("details", Map.of("libreoffice", module));
        return ResponseEntity.status(up ? 200 : 503).body(body);
    }

    /** Tjänstens version som text, som Gotenbergs {@code /version}. Obs: det är inte en Gotenberg-version. */
    @GetMapping(value = "/version", produces = MediaType.TEXT_PLAIN_VALUE)
    public String version() {
        return build.version();
    }
}

package se.fileconverter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.fileconverter.observability.ConversionTelemetry;

/**
 * Gotenberg-routes som inte finns här. De besvaras med 404 som tidigare, men räknas i
 * {@code converter_unknown_routes_total} och loggas, så att det syns om en klient anropar dem efter bytet.
 */
@RestController
public class UnknownRoutes {

    /** Gotenberg 8:s routes. Andra sökvägar räknas som "okand", så att klienter inte kan skapa nya serier. */
    private static final Set<String> GOTENBERG_ROUTES = Set.of(
            "/forms/chromium/convert/url", "/forms/chromium/convert/html", "/forms/chromium/convert/markdown",
            "/forms/chromium/screenshot/url", "/forms/chromium/screenshot/html", "/forms/chromium/screenshot/markdown",
            "/forms/libreoffice/convert", "/forms/pdfengines/convert", "/forms/pdfengines/merge",
            "/forms/pdfengines/split", "/forms/pdfengines/flatten", "/forms/pdfengines/metadata/read",
            "/forms/pdfengines/metadata/write", "/forms/pdfengines/encrypt", "/forms/pdfengines/embed",
            "/version", "/prometheus/metrics", "/debug");
    private static final Logger log = LoggerFactory.getLogger(UnknownRoutes.class);

    private final ConversionTelemetry telemetry;

    public UnknownRoutes(ConversionTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @RequestMapping({"/forms/**", "/version", "/prometheus/metrics", "/debug"})
    public void unknown(HttpServletRequest request) {
        String path = request.getRequestURI();
        String route = GOTENBERG_ROUTES.contains(path) ? path : "okand";
        String method = List.of("GET", "POST").contains(request.getMethod()) ? request.getMethod() : "annan";
        telemetry.unknownRoute(route, method);
        log.warn("Route som inte finns: {} {}", request.getMethod(), path.length() > 200 ? path.substring(0, 200) : path);
        throw new ConversionException(HttpStatus.NOT_FOUND, ConversionException.Reason.UNSUPPORTED_ROUTE,
                "Routen " + request.getMethod() + " " + route + " finns inte i den här tjänsten. Se README för vilka routes som stöds.",
                List.of(), null);
    }
}

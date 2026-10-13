package se.fileconverter.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.observability.ConversionTelemetry;

/**
 * Begränsar antalet samtidiga konverteringar per instans. Utan gräns kan några stora filer samtidigt
 * ta slut på minnet, eftersom tjänsten tar emot obegränsat många anrop (virtuella trådar).
 * Anrop över gränsen köar en stund och får sedan 503 med Retry-After, så att klienten kan försöka
 * igen eller Kubernetes skala upp.
 */
@Configuration
public class ConcurrencyLimit implements HandlerInterceptor, WebMvcConfigurer {

    /** Markerar en handler-metod som en konvertering som räknas mot gränsen. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Limited {
    }

    private static final String ACQUIRED = ConcurrencyLimit.class.getName() + ".acquired";

    private final Semaphore permits;
    private final long queueTimeoutMillis;

    private final ConversionTelemetry telemetry;

    public ConcurrencyLimit(ConverterProperties properties, ConversionTelemetry telemetry) {
        this.telemetry = telemetry;
        ConverterProperties.Limits limits = properties.limits();
        int max = limits.maxConcurrent() > 0 ? limits.maxConcurrent() : Runtime.getRuntime().availableProcessors();
        this.permits = new Semaphore(max, true);
        this.queueTimeoutMillis = limits.queueTimeout().toMillis();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(Limited.class)) {
            return true;
        }
        if (!permits.tryAcquire(queueTimeoutMillis, TimeUnit.MILLISECONDS)) {
            telemetry.busy();
            response.setHeader("Retry-After", "10");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Tjänsten är upptagen. Försök igen om en stund.");
            return false;
        }
        request.setAttribute(ACQUIRED, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(ACQUIRED) != null) {
            request.removeAttribute(ACQUIRED);
            permits.release();
        }
    }
}

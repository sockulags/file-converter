package se.fileconverter.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import se.fileconverter.config.ConverterProperties;

/**
 * Skyddar administrationen med Basic Auth mot {@code converter.admin.password}. Utan lösenord finns
 * administrationen inte.
 *
 * <p>Kontrollen görs på vilken controller anropet hamnar i, inte på URL:en. En URL-baserad kontroll
 * gick att kringgå med {@code /%61dmin/...} eller {@code /admin;x/...}, som Spring ändå skickar
 * till administrationen. Gränssnittet levereras därför också av {@link AdminController}.
 *
 * <p>Ändrande anrop kräver dessutom huvudet {@code X-Requested-With}, som en annan webbplats inte
 * kan sätta i ett formulär. Det hindrar CSRF mot inloggade webbläsare.
 */
@Configuration
public class AdminAuthInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    public static final String CSRF_HEADER = "X-Requested-With";
    private static final String USER = "admin";

    private final ConverterProperties.Admin config;

    public AdminAuthInterceptor(ConverterProperties properties) {
        this.config = properties.admin();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method) || method.getBeanType() != AdminController.class) {
            return true;
        }
        if (!config.enabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        if (!authorized(request.getHeader("Authorization"))) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"file-converter admin\", charset=\"UTF-8\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        boolean mutating = !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD");
        if (mutating && request.getHeader(CSRF_HEADER) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Huvudet " + CSRF_HEADER + " saknas");
            return false;
        }
        response.setHeader("Cache-Control", "no-store");
        return true;
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Basic ")) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        boolean userOk = MessageDigest.isEqual(USER.getBytes(StandardCharsets.UTF_8),
                decoded.substring(0, colon).getBytes(StandardCharsets.UTF_8));
        boolean passwordOk = MessageDigest.isEqual(config.password().getBytes(StandardCharsets.UTF_8),
                decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8));
        return userOk & passwordOk;
    }
}

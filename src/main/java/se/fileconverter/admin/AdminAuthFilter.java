package se.fileconverter.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import se.fileconverter.config.ConverterProperties;

/**
 * Skyddar /admin med Basic Auth mot {@code converter.admin.password}. Utan lösenord finns /admin inte.
 *
 * <p>Ändrande anrop kräver dessutom huvudet {@code X-Requested-With}, som en annan webbplats inte
 * kan sätta i ett formulär. Det hindrar CSRF mot inloggade webbläsare.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-Requested-With";
    private static final String USER = "admin";

    private final ConverterProperties.Admin config;

    public AdminAuthFilter(ConverterProperties properties) {
        this.config = properties.admin();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/admin") || path.startsWith("/admin/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!config.enabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!authorized(request.getHeader("Authorization"))) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"file-converter admin\", charset=\"UTF-8\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        boolean mutating = !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD");
        if (mutating && request.getHeader(CSRF_HEADER) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Huvudet " + CSRF_HEADER + " saknas");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
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

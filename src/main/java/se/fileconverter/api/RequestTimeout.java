package se.fileconverter.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * Tidsgränsen för hela anropet ({@code converter.limits.request-timeout}) för handler-metoder
 * markerade med {@link ConcurrencyLimit.Limited}.
 *
 * <p>Bara själva metoden körs med tidsgränsen. Argumenten tolkas och svaret skrivs i anropets egen
 * tråd, så att en konvertering som fortsätter efter tidsgränsen aldrig rör svaret.
 */
@Configuration
public class RequestTimeout {

    @Bean
    WebMvcRegistrations requestTimeoutRegistrations(@Lazy Timeouts timeouts) {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
                return new RequestMappingHandlerAdapter() {
                    @Override
                    protected ServletInvocableHandlerMethod createInvocableHandlerMethod(HandlerMethod handlerMethod) {
                        if (!handlerMethod.hasMethodAnnotation(ConcurrencyLimit.Limited.class)) {
                            return super.createInvocableHandlerMethod(handlerMethod);
                        }
                        return new ServletInvocableHandlerMethod(handlerMethod) {
                            @Override
                            protected Object doInvoke(Object... args) throws Exception {
                                return timeouts.request(route(), () -> super.doInvoke(args));
                            }
                        };
                    }
                };
            }
        };
    }

    private static String route() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String route = ConcurrencyLimit.route(request);
            return route != null ? route : "okand";
        }
        return "okand";
    }
}

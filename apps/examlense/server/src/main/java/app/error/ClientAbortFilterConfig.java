package app.error;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.EnumSet;

@Configuration
public class ClientAbortFilterConfig {

    /**
     * Registered by hand rather than as a {@code @Component} for the dispatcher
     * types: a client disconnect surfaces on the ASYNC/ERROR dispatch that
     * finishes the abandoned request, and Spring Boot's default registration
     * covers REQUEST only. Outermost order so it wraps the security chain and
     * therefore everything that can throw.
     */
    @Bean
    public FilterRegistrationBean<ClientAbortFilter> clientAbortFilter() {
        FilterRegistrationBean<ClientAbortFilter> reg =
            new FilterRegistrationBean<>(new ClientAbortFilter());
        reg.setDispatcherTypes(EnumSet.of(
            DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}

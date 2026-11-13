package de.tum.cit.hestia.materials.flow;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The login of the flow tests. The application only knows the SAML login, which needs an identity
 * provider, so the flow tests get a login request that exists in tests only: a POST to
 * {@link #LOGIN_PATH} puts a logged-in user into the HTTP session and answers 204. Everything else
 * runs through the real security chain, so a client without that session still gets its 401.
 */
@TestConfiguration(proxyBeanMethods = false)
class FlowLoginConfiguration {

    static final String LOGIN_PATH = "/flow-test-login";

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> flowTestLoginFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                if (!LOGIN_PATH.equals(request.getRequestURI()) || !"POST".equals(request.getMethod())) {
                    chain.doFilter(request, response);
                    return;
                }
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "flow-test-user", null, List.of()));
                request.getSession(true).setAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            }
        });
        // Before Spring Security's filter chain, which is what the session login is read by.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}

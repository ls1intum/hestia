package de.tum.cit.hestia.core;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import de.tum.cit.hestia.core.internal.identity.HestiaAuthentication;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Logins for tests, through the same {@link IdentityService} as the SAML login: the organisation and
 * the user are created on first use. Organisations are given as schacHomeOrganization values
 * (e.g. {@code "uni-a.test"}), users as external ids.
 *
 * <pre>{@code
 * @Autowired IdentityService identities;
 * mvc.perform(post("/learninggoalhub/api/courses").with(TestLogin.as(identities, "uni-a.test", "alice")))
 * }</pre>
 */
public final class TestLogin {

    private TestLogin() {}

    /** Logs the user in and returns the principal (to read its user and organisation id). */
    public static HestiaPrincipal login(IdentityService identities, String org, String user) {
        return identities.login(org, user, user);
    }

    /** A MockMvc request of that user, with a valid CSRF token. */
    public static RequestPostProcessor as(IdentityService identities, String org, String user) {
        HestiaAuthentication auth = new HestiaAuthentication(login(identities, org, user));
        return request -> csrf().postProcessRequest(authentication(auth).postProcessRequest(request));
    }

    /**
     * Runs {@code action} as that user outside a request (e.g. to set up data through a service):
     * with the user in the security context and the organisation in {@link OrgContext}.
     */
    public static <T> T runAs(IdentityService identities, String org, String user, Supplier<T> action) {
        HestiaPrincipal principal = login(identities, org, user);
        var previousAuth = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new HestiaAuthentication(principal));
        var previousOrg = OrgContext.set(principal.orgId());
        try {
            return action.get();
        } finally {
            OrgContext.set(previousOrg);
            SecurityContextHolder.getContext().setAuthentication(previousAuth);
        }
    }
}

package de.tum.cit.hestia.core.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class DevAuthFilterTest {

    private final IdentityService identities = mock(IdentityService.class);

    @BeforeEach
    void everyLoginIsAnAdmin() {
        // As if every external id were listed in hestia.security.platform-admins.
        when(identities.login(anyString(), anyString(), anyString())).thenAnswer(call -> new HestiaPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), call.getArgument(1), call.getArgument(2), true, null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private HestiaPrincipal loginWith(boolean impersonation) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/examlense/api/exams");
        request.addHeader(DevAuthFilter.USER_HEADER, "someone-else");
        request.addHeader(DevAuthFilter.ORG_HEADER, "other.test");
        new DevAuthFilter(identities, impersonation).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return (HestiaPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @Test
    void withoutTheSwitchTheHeadersAreIgnored() throws Exception {
        assertThat(loginWith(false).externalId()).isEqualTo(DevAuthFilter.DEFAULT_USER);
    }

    @Test
    void withTheSwitchTheHeadersChooseTheUser() throws Exception {
        assertThat(loginWith(true).externalId()).isEqualTo("someone-else");
    }

    @Test
    void theDevelopmentLoginIsNeverAnAdmin() throws Exception {
        assertThat(loginWith(false).admin()).isFalse();
        SecurityContextHolder.clearContext();
        assertThat(loginWith(true).admin()).isFalse();
    }
}

package app.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static MockHttpServletResponse hit(RateLimitFilter filter, String remoteAddr, String forwardedFor)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/exams");
        req.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) req.addHeader("X-Forwarded-For", forwardedFor);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void overLimitRequestsGet429() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2, 10, false);
        assertThat(hit(filter, "1.2.3.4", null).getStatus()).isEqualTo(200);
        assertThat(hit(filter, "1.2.3.4", null).getStatus()).isEqualTo(200);

        MockHttpServletResponse third = hit(filter, "1.2.3.4", null);
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getContentAsString()).contains("Too many requests");

        // A different IP has its own window.
        assertThat(hit(filter, "5.6.7.8", null).getStatus()).isEqualTo(200);
    }

    @Test
    void forwardedForIsIgnoredWhenNotBehindProxy() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2, 10, false);
        // Rotating spoofed X-Forwarded-For must not evade the limit.
        assertThat(hit(filter, "1.2.3.4", "9.9.9.1").getStatus()).isEqualTo(200);
        assertThat(hit(filter, "1.2.3.4", "9.9.9.2").getStatus()).isEqualTo(200);
        assertThat(hit(filter, "1.2.3.4", "9.9.9.3").getStatus()).isEqualTo(429);
    }

    @Test
    void forwardedForIsHonoredBehindProxy() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 10, true);
        assertThat(hit(filter, "10.0.0.1", "9.9.9.1").getStatus()).isEqualTo(200);
        // Same proxy address, different client → separate window.
        assertThat(hit(filter, "10.0.0.1", "9.9.9.2").getStatus()).isEqualTo(200);
        // Same client again → limited.
        assertThat(hit(filter, "10.0.0.1", "9.9.9.1").getStatus()).isEqualTo(429);
    }

    @Test
    void optionsPreflightIsNeverLimited() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 10, false);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/exams");
            req.setRemoteAddr("1.2.3.4");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }
}

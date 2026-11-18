package de.tum.cit.hestia.teaching.flow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Without a signed-in caller the API answers 401 and creates nothing. */
@Tag("http-flow")
@ActiveProfiles(FlowAuth.ANONYMOUS_PROFILE)
class UnauthenticatedHttpFlowTest extends FlowTestSupport {

    @Test
    void readsAndWritesAreRejectedWith401() {
        FlowClient api = anonymous();
        assertThat(api.get(FlowPaths.COURSES).status()).isEqualTo(401);
        assertThat(api.get(FlowPaths.SESSIONS).status()).isEqualTo(401);
        assertThat(api.post(FlowPaths.COURSE_DRAFT, Map.of("title", "Nope")).status()).isEqualTo(401);
        assertThat(api.post(FlowPaths.SESSION_DRAFT, Map.of("title", "Nope")).status()).isEqualTo(401);
    }
}

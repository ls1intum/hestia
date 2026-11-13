package de.tum.cit.hestia.assessment.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Protected endpoints answer 401 without a valid credential; the health probe stays open. */
@Tag("http-flow")
class UnauthenticatedAccessHttpFlowTest extends AbstractHttpFlowTest {

    @Test
    void listExams_withoutCredential_is401() {
        assertThat(http.getUnauthenticated(FlowPaths.api("/exams")).status()).isEqualTo(401);
    }

    @Test
    void listExams_withInvalidCredential_is401() {
        assertThat(http.getWithInvalidCredential(FlowPaths.api("/exams")).status()).isEqualTo(401);
    }

    @Test
    void createExam_withoutCredential_is401() {
        FlowHttp.Reply res = http.postJsonUnauthenticated(FlowPaths.api("/exams"),
            Map.of("title", "nope", "source", "manual"));
        assertThat(res.status()).isEqualTo(401);
    }

    @Test
    void healthProbe_isOpen() {
        assertThat(http.getUnauthenticated(FlowPaths.api("/healthz")).status()).isEqualTo(200);
    }
}

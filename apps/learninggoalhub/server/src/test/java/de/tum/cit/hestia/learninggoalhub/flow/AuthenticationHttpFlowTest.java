package de.tum.cit.hestia.learninggoalhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The API needs a login: callers without one get a 401 and reach nothing. */
@Tag("http-flow")
class AuthenticationHttpFlowTest extends HttpFlowTestBase {

    @Test
    void callsWithoutCredentialsAreRejectedWith401() {
        long courseId = createCourse("Flow test: auth");

        assertThat(anonymousApi().get("/courses").status()).isEqualTo(401);
        assertThat(anonymousApi().get("/courses/" + courseId).status()).isEqualTo(401);
        assertThat(anonymousApi().postJson("/courses", Map.of("name", "Not created")).status()).isEqualTo(401);
        assertThat(anonymousApi().get("/courses/" + courseId + "/learning-goals").status()).isEqualTo(401);
        assertThat(anonymousApi().post("/courses/" + courseId + "/extract").status()).isEqualTo(401);
        assertThat(anonymousApi().upload("/courses/" + courseId + "/documents",
                Map.of("a.pdf", TestPdfs.textPdf(TestPdfs.SEARCHING_LECTURE)), "application/pdf").status())
                .isEqualTo(401);

        // The same requests with credentials succeed, so the 401s above are about the login.
        assertThat(api().get("/courses/" + courseId).status()).isEqualTo(200);
        assertThat(api().get("/courses/" + courseId + "/documents").json().size()).isZero();
    }
}

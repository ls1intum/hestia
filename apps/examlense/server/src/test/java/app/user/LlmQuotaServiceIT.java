package app.user;

import app.AbstractIntegrationTest;
import app.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Per-user LLM metering. The point of the change was that limits used to be
 * global — a per-IP request filter that cannot know who is calling, and a shared
 * thread pool — so the load-bearing assertion here is that one user exhausting
 * their quota leaves everyone else unaffected.
 */
@TestPropertySource(properties = {
    "app.quota.parse-per-day=2", "app.quota.solve-per-day=1",
    // High enough that the per-user assertions below are not the global ceiling
    // firing by accident; ClosedRegistrationIT's sibling covers the ceiling itself.
    "app.quota.global-parse-per-day=10000", "app.quota.global-solve-per-day=10000",
})
class LlmQuotaServiceIT extends AbstractIntegrationTest {

    @Autowired LlmQuotaService quota;

    private String freshUser() {
        return createUser("quota-" + UUID.randomUUID()).id().toString();
    }

    @Test
    void callsUpToTheLimitAreAdmittedAndTheNextIsRejected() {
        String user = freshUser();

        quota.checkAndRecord(user, LlmQuotaService.KIND_PARSE);
        quota.checkAndRecord(user, LlmQuotaService.KIND_PARSE);

        assertThatThrownBy(() -> quota.checkAndRecord(user, LlmQuotaService.KIND_PARSE))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void oneUserExhaustingTheirQuotaDoesNotAffectAnother() {
        String heavy = freshUser();
        String light = freshUser();
        quota.checkAndRecord(heavy, LlmQuotaService.KIND_SOLVE);

        assertThatThrownBy(() -> quota.checkAndRecord(heavy, LlmQuotaService.KIND_SOLVE))
            .isInstanceOf(ApiException.class);
        assertThatCode(() -> quota.checkAndRecord(light, LlmQuotaService.KIND_SOLVE))
            .doesNotThrowAnyException();
    }

    @Test
    void quotasAreTrackedPerKindNotShared() {
        String user = freshUser();
        quota.checkAndRecord(user, LlmQuotaService.KIND_SOLVE); // solve limit is 1

        assertThatCode(() -> quota.checkAndRecord(user, LlmQuotaService.KIND_PARSE))
            .doesNotThrowAnyException();
    }

    @Test
    void remainingCountsReflectConsumption() {
        String user = freshUser();
        assertThat(quota.remainingFor(user).parse_remaining()).isEqualTo(2);

        quota.checkAndRecord(user, LlmQuotaService.KIND_PARSE);

        assertThat(quota.remainingFor(user).parse_remaining()).isEqualTo(1);
        assertThat(quota.remainingFor(user).parse_limit()).isEqualTo(2);
    }

    /** check() must not consume, or the solve path would charge twice. */
    @Test
    void checkAloneDoesNotConsumeQuota() {
        String user = freshUser();

        quota.check(user, LlmQuotaService.KIND_PARSE);
        quota.check(user, LlmQuotaService.KIND_PARSE);
        quota.check(user, LlmQuotaService.KIND_PARSE);

        assertThat(quota.remainingFor(user).parse_remaining()).isEqualTo(2);
    }

    @Test
    void anExhaustedUserIsRefusedByCheckBeforeAnythingIsRecorded() {
        String user = freshUser();
        quota.checkAndRecord(user, LlmQuotaService.KIND_SOLVE);

        assertThatThrownBy(() -> quota.check(user, LlmQuotaService.KIND_SOLVE))
            .isInstanceOf(ApiException.class);
    }
}

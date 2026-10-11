package app.user;

import app.AbstractIntegrationTest;
import app.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The instance-wide ceiling on language-model usage.
 *
 * <p>It exists because per-user quotas bound nothing on their own: registration is
 * open, so a used-up account can be traded for a fresh allowance, and the per-IP
 * registration cap that slows that down depends on proxy headers. This ceiling is
 * counted from actual usage, so it holds regardless of how many accounts appear.
 */
@TestPropertySource(properties = {
    "app.quota.parse-per-day=100", "app.quota.solve-per-day=100",
    "app.quota.global-parse-per-day=3", "app.quota.global-solve-per-day=2",
})
class GlobalLlmCeilingIT extends AbstractIntegrationTest {

    @Autowired LlmQuotaService quota;
    @Autowired LlmUsageRepository usage;

    /**
     * The ceiling counts every account's usage, so rows left behind by other test
     * classes would consume it. Safe to clear: this is the only class asserting on
     * instance-wide totals, and the suite runs classes sequentially.
     */
    @BeforeEach
    void clearUsage() {
        usage.deleteAll();
    }

    private String freshUser() {
        return createUser("global-" + UUID.randomUUID()).id().toString();
    }

    /** The property this whole control exists for. */
    @Test
    void spreadingUsageAcrossNewAccountsDoesNotGetPastIt() {
        // Three different accounts, each well inside its own generous per-user quota.
        quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_PARSE);
        quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_PARSE);
        quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_PARSE);

        String yetAnother = freshUser();
        assertThatThrownBy(() -> quota.checkAndRecord(yetAnother, LlmQuotaService.KIND_PARSE))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    /**
     * The two refusals need different wording: "you are out" and "the instance is
     * out" call for different actions from whoever reads them.
     */
    @Test
    void theCeilingSaysSoRatherThanBlamingTheUsersOwnQuota() {
        quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_SOLVE);
        quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_SOLVE);

        assertThatThrownBy(() -> quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_SOLVE))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("across all users");
    }

    @Test
    void theCeilingIsPerKind() {
        quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_SOLVE);
        quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_SOLVE);

        assertThatThrownBy(() -> quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_SOLVE))
            .isInstanceOf(ApiException.class);
        // Parsing has its own ceiling and is unaffected.
        assertThatCode(() -> quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_PARSE))
            .doesNotThrowAnyException();
    }

    @Test
    void usageUpToTheCeilingIsStillAdmitted() {
        assertThatCode(() -> {
            quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_PARSE);
            quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_PARSE);
            quota.checkAndRecord(freshUser(), LlmQuotaService.KIND_PARSE);
        }).doesNotThrowAnyException();
    }

    /** check() must stay non-consuming, or the solve path would charge twice. */
    @Test
    void checkAloneDoesNotConsumeTheGlobalAllowance() {
        String user = freshUser();
        quota.check(user, LlmQuotaService.KIND_PARSE);
        quota.check(user, LlmQuotaService.KIND_PARSE);
        quota.check(user, LlmQuotaService.KIND_PARSE);
        quota.check(user, LlmQuotaService.KIND_PARSE);

        assertThatCode(() -> quota.checkAndRecord(user, LlmQuotaService.KIND_PARSE))
            .doesNotThrowAnyException();
    }
}

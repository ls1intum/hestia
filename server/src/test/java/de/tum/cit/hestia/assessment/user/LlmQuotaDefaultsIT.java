package de.tum.cit.hestia.assessment.user;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Without configuration no LLM limit applies, but usage is still counted. */
class LlmQuotaDefaultsIT extends AbstractIntegrationTest {

    @Autowired LlmQuotaService quota;

    @Test
    void noLimitByDefaultAndUsageIsCounted() {
        UUID user = userId("heavy-" + UUID.randomUUID());
        for (int i = 0; i < 25; i++) {
            quota.checkAndRecord(user, LlmQuotaService.KIND_PARSE);
        }
        quota.checkAndRecord(user, LlmQuotaService.KIND_SOLVE);

        UserDtos.QuotaResponse usage = quota.remainingFor(user);
        assertThat(usage.parse_limit()).isZero();
        assertThat(usage.parse_used()).isEqualTo(25);
        assertThat(usage.solve_used()).isEqualTo(1);
    }
}

package de.tum.cit.hestia.assessment.user;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Without configuration no per-user limit applies and usage is still counted; the organisation
 * ceiling stays on (200 per day).
 */
class LlmQuotaDefaultsIT extends AbstractIntegrationTest {

    @Autowired LlmQuotaService quota;

    @Test
    void noPerUserLimitByDefaultAndUsageIsCounted() {
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

    @Test
    void theOrganisationCeilingIsOnByDefault() {
        Object target = AopTestUtils.getTargetObject(quota); // the bean is a transactional proxy
        assertThat(ReflectionTestUtils.getField(target, "globalParsePerDay")).isEqualTo(200);
        assertThat(ReflectionTestUtils.getField(target, "globalSolvePerDay")).isEqualTo(200);
    }
}

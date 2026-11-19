package de.tum.cit.hestia.core.internal.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeployGuardTest {

    @Test
    void localDevelopmentMayImpersonate() {
        assertThatCode(() -> DeployGuard.check(List.of("local"), null, true)).doesNotThrowAnyException();
    }

    @Test
    void stagingMayRunTheFixedDevelopmentLogin() {
        assertThatCode(() -> DeployGuard.check(List.of("Staging", "local"), "Staging", false))
                .doesNotThrowAnyException();
    }

    @Test
    void productionWithoutDevelopmentLoginStarts() {
        assertThatCode(() -> DeployGuard.check(List.of("Production", "prod"), "Production", false))
                .doesNotThrowAnyException();
    }

    @Test
    void localWithProdIsRefused() {
        assertThatThrownBy(() -> DeployGuard.check(List.of("local", "prod"), null, false))
                .isInstanceOf(IllegalStateException.class);
    }

    /** SPRING_PROFILES_ACTIVE=local on the Production VM: the deploy target still tells. */
    @Test
    void localOnTheProductionTargetIsRefused() {
        assertThatThrownBy(() -> DeployGuard.check(List.of("local"), "Production", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void impersonationOnStagingIsRefused() {
        assertThatThrownBy(() -> DeployGuard.check(List.of("Staging", "local"), "Staging", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("impersonation");
    }

    /** Without the development login the switch has no effect, so it does not block a start. */
    @Test
    void impersonationWithoutDevelopmentLoginIsHarmless() {
        assertThatCode(() -> DeployGuard.check(List.of("prod"), "Production", true)).doesNotThrowAnyException();
    }
}

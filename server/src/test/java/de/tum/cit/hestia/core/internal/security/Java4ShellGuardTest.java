package de.tum.cit.hestia.core.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Java4ShellGuardTest {

    private static final Runtime.Version OPEN = Runtime.Version.parse("21.0.13+9");
    private static final Runtime.Version FIXED = Runtime.Version.parse("21.0.13.1+2");

    @Test
    void openJvmWithTheIntrinsicOnIsRefusedOnADeployTarget() {
        assertThatThrownBy(() -> Java4ShellGuard.check("Production", OPEN, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CVE-2026-114938");
        assertThatThrownBy(() -> Java4ShellGuard.check("Staging", OPEN, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void openJvmWithTheIntrinsicOffStarts() {
        assertThatCode(() -> Java4ShellGuard.check("Production", OPEN, false)).doesNotThrowAnyException();
    }

    @Test
    void fixedJvmStartsEvenWithTheIntrinsicOn() {
        assertThatCode(() -> Java4ShellGuard.check("Production", FIXED, true)).doesNotThrowAnyException();
    }

    @Test
    void localDevelopmentIsNotChecked() {
        assertThatCode(() -> Java4ShellGuard.check(null, OPEN, true)).doesNotThrowAnyException();
        assertThatCode(() -> Java4ShellGuard.check(" ", OPEN, true)).doesNotThrowAnyException();
    }

    @Test
    void onlyTheTwentyOneLineFromTheFixOnCountsAsFixed() {
        assertThat(Java4ShellGuard.isFixed(Runtime.Version.parse("21.0.13.1+2"))).isTrue();
        assertThat(Java4ShellGuard.isFixed(Runtime.Version.parse("21.0.14+7"))).isTrue();
        assertThat(Java4ShellGuard.isFixed(Runtime.Version.parse("21.0.13+9"))).isFalse();
        assertThat(Java4ShellGuard.isFixed(Runtime.Version.parse("21.0.6+7"))).isFalse();
        assertThat(Java4ShellGuard.isFixed(Runtime.Version.parse("25.0.2+10"))).isFalse();
    }
}

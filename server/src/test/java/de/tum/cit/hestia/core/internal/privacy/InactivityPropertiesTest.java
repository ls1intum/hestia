package de.tum.cit.hestia.core.internal.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class InactivityPropertiesTest {

    @Test
    void defaultsMatchThePrivacyPolicy() {
        var properties = new InactivityProperties(true, false, null, 0, null);
        assertThat(properties.warnAfterDays()).containsExactly(300, 350);
        assertThat(properties.deleteAfterDays()).isEqualTo(365);
        assertThat(properties.lastWarning()).isEqualTo(350);
        assertThat(properties.notifier()).isEqualTo("log");
    }

    @Test
    void thresholdsAreSorted() {
        assertThat(new InactivityProperties(true, false, List.of(350, 300), 365, "log").warnAfterDays())
                .containsExactly(300, 350);
    }

    @Test
    void everyWarningMustComeBeforeTheDeletion() {
        assertThatThrownBy(() -> new InactivityProperties(true, false, List.of(300, 365), 365, "log"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

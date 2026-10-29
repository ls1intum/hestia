package de.tum.cit.hestia.learninggoalhub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LlmRateLimitFilterTest {

    @Test
    void allowsTheLimitPerWindowAndResetsAfterIt() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        LlmRateLimitFilter limiter = new LlmRateLimitFilter(request -> true, 2, Duration.ofMinutes(10), clock);

        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isEqualTo(600);
        assertThat(limiter.tryAcquire("b")).isZero();

        now.set(now.get().plus(Duration.ofMinutes(4)));
        assertThat(limiter.tryAcquire("a")).isEqualTo(360);

        now.set(now.get().plus(Duration.ofMinutes(6)));
        assertThat(limiter.tryAcquire("a")).isZero();
    }
}

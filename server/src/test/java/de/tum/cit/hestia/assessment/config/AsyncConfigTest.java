package de.tum.cit.hestia.assessment.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.core.OrgContext;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Learning-goal generation runs on the lghExecutor after a section is confirmed, and the goal
 * service checks the caller's course role. The task therefore needs the submitting request's
 * login as well as its organisation; without it every run fails with "Not logged in".
 */
class AsyncConfigTest {

    private record Seen(Optional<UUID> org, Authentication auth) {}

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        OrgContext.set(null);
    }

    @Test
    void lghExecutorCarriesLoginAndOrganisation() throws Exception {
        Executor executor = new AsyncConfig().lghExecutor();
        Authentication login = new TestingAuthenticationToken("lecturer", "n/a", "ROLE_USER");
        UUID org = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(login);
        OrgContext.set(org);

        try {
            Seen seen = CompletableFuture.supplyAsync(
                    () -> new Seen(OrgContext.current(), SecurityContextHolder.getContext().getAuthentication()),
                    executor).get(5, TimeUnit.SECONDS);

            assertThat(seen.org()).contains(org);
            assertThat(seen.auth()).isSameAs(login);
        } finally {
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
    }

    @Test
    void lghExecutorDoesNotLeakLoginToLaterTasks() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().lghExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    new TestingAuthenticationToken("lecturer", "n/a", "ROLE_USER"));
            CompletableFuture.runAsync(() -> { }, executor).get(5, TimeUnit.SECONDS);

            SecurityContextHolder.clearContext();
            Authentication later = CompletableFuture.supplyAsync(
                    () -> SecurityContextHolder.getContext().getAuthentication(), executor).get(5, TimeUnit.SECONDS);

            assertThat(later).isNull();
        } finally {
            executor.shutdown();
        }
    }
}

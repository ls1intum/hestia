package de.tum.cit.hestia.teaching.facade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import de.tum.cit.hestia.core.CurrentUser;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UsageGuardTest {

    private final CurrentUser user = Mockito.mock(CurrentUser.class);

    @BeforeEach
    void signIn() {
        Mockito.when(user.userId()).thenReturn(UUID.randomUUID());
    }

    @Test
    void limitsAiRequestsPerWindow() throws Exception {
        UsageGuard guard = new UsageGuard(user, 4, 3, 10);
        for (int i = 0; i < 3; i++) assertEquals("ok", guard.runAi(() -> "ok"));
        var ex = assertThrows(ResponseStatusException.class, () -> guard.runAi(() -> "ok"));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void limitsConcurrentAiRequestsPerUser() throws Exception {
        UsageGuard guard = new UsageGuard(user, 1, 100, 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<String> first = pool.submit(() -> {
            return guard.runAi(() -> { started.countDown(); release.await(5, TimeUnit.SECONDS); return "first"; });
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var ex = assertThrows(ResponseStatusException.class, () -> guard.runAi(() -> "second"));
        assertEquals(429, ex.getStatusCode().value());
        release.countDown();
        assertEquals("first", first.get(5, TimeUnit.SECONDS));
        assertEquals("third", guard.runAi(() -> "third")); // the slot is free again
        pool.shutdown();
    }

    @Test
    void rejectsOversizedText() {
        UsageGuard guard = new UsageGuard(user, 4, 100, 10);
        var ex = assertThrows(ResponseStatusException.class, () -> guard.checkGoalTexts(List.of("x".repeat(10_000))));
        assertEquals(400, ex.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> UsageGuard.checkTitle("t".repeat(1_000)));
        assertThrows(ResponseStatusException.class,
                () -> UsageGuard.checkStoredSize("The session", "a".repeat(UsageGuard.MAX_DRAFT_CHARS + 1), UsageGuard.MAX_DRAFT_CHARS));
    }
}

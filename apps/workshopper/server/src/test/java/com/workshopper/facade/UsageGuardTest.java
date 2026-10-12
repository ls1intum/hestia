package com.workshopper.facade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UsageGuardTest {

    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user-A", null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void limitsAiRequestsPerWindow() throws Exception {
        UsageGuard guard = new UsageGuard(4, 3, 10);
        for (int i = 0; i < 3; i++) assertEquals("ok", guard.runAi(() -> "ok"));
        var ex = assertThrows(ResponseStatusException.class, () -> guard.runAi(() -> "ok"));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void limitsConcurrentAiRequestsPerUser() throws Exception {
        UsageGuard guard = new UsageGuard(1, 100, 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Future<String> first = pool.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
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
        UsageGuard guard = new UsageGuard(4, 100, 10);
        var ex = assertThrows(ResponseStatusException.class, () -> guard.checkGoalTexts(List.of("x".repeat(10_000))));
        assertEquals(400, ex.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> UsageGuard.checkTitle("t".repeat(1_000)));
        assertThrows(ResponseStatusException.class,
                () -> UsageGuard.checkStoredSize("The session", "a".repeat(UsageGuard.MAX_DRAFT_CHARS + 1), UsageGuard.MAX_DRAFT_CHARS));
    }
}

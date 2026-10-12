package com.workshopper.facade;

import com.workshopper.config.AuthContext;
import com.workshopper.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Limits on what a user can make the server do, so one user (by accident or on purpose) can't
 * burn LLM credits, starve other users or fill the database:
 * <ul>
 *   <li>AI requests: bounded input sizes, at most {@code maxConcurrent} at a time and
 *       {@code maxPerWindow} per {@code windowMinutes} per user (429 when exceeded)</li>
 *   <li>stored data: bounded draft and slide sizes (413) and number of sessions/courses (400)</li>
 * </ul>
 * The limits are far above normal use (a 90-minute session has about 10 blocks and 3–6 goals).
 */
@Component
public class UsageGuard {

    static final int MAX_BLOCKS = 40;
    static final int MAX_GOALS = 20;
    static final int MAX_TEXT = 3_000;
    static final int MAX_DURATION = 600;
    static final int MAX_ACTIVITIES = 40;
    static final int MAX_STEPS_PER_BLOCK = 60;
    public static final int MAX_DRAFT_CHARS = 2_000_000;
    public static final int MAX_SLIDES_CHARS = 5_000_000;
    public static final int MAX_SESSIONS_PER_USER = 500;
    public static final int MAX_COURSES_PER_USER = 100;

    private final int maxConcurrent;
    private final int maxPerWindow;
    private final long windowMillis;
    private final Map<String, Semaphore> running = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public UsageGuard(
            @Value("${workshopper.limits.ai-concurrent-per-user:4}") int maxConcurrent,
            @Value("${workshopper.limits.ai-requests-per-window:120}") int maxPerWindow,
            @Value("${workshopper.limits.ai-window-minutes:10}") int windowMinutes) {
        this.maxConcurrent = maxConcurrent;
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMinutes * 60_000L;
    }

    @FunctionalInterface
    public interface AiCall<T> {
        T call() throws Exception;
    }

    /** Runs an AI request for the current user within their limits. */
    public <T> T runAi(AiCall<T> call) throws Exception {
        String user = AuthContext.getCurrentUserId();
        countRequest(user);
        Semaphore slots = running.computeIfAbsent(user, u -> new Semaphore(maxConcurrent));
        if (!slots.tryAcquire()) {
            throw tooMany("Too many AI requests are running at once. Please wait for them to finish.");
        }
        try {
            return call.call();
        } finally {
            slots.release();
        }
    }

    private void countRequest(String user) {
        long now = System.currentTimeMillis();
        Deque<Long> times = recent.computeIfAbsent(user, u -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > windowMillis) times.pollFirst();
            if (times.size() >= maxPerWindow) {
                throw tooMany("You have made a lot of AI requests in a short time. Please wait a few minutes and try again.");
            }
            times.addLast(now);
        }
    }

    // ── Input checks ──────────────────────────────────────────────────────

    public void checkGeneration(GenerateSessionRequestDto request) {
        checkGoals(request.goals());
        checkMeta(request.meta());
        checkText("Available materials", request.availableMaterials());
        if (request.skeleton() != null && request.skeleton().blocks() != null) {
            List<SkeletonBlockDto> blocks = request.skeleton().blocks();
            require(blocks.size() <= MAX_BLOCKS, "A timetable can have at most " + MAX_BLOCKS + " blocks.");
            for (SkeletonBlockDto b : blocks) {
                checkText("Block title", b.title());
                checkText("Block description", b.description());
                require(b.duration() >= 0 && b.duration() <= MAX_DURATION, "A block can last at most " + MAX_DURATION + " minutes.");
                require(b.sections() == null || b.sections().size() <= 20, "A block can have at most 20 sections.");
            }
        }
    }

    public void checkBlockSlides(ActivityBlockDto block, WorkshopInputDto meta, List<LearningGoalPlanDto> goals) {
        checkGoals(goals);
        checkMeta(meta);
        if (block == null) return;
        checkText("Block title", block.phaseLabel());
        checkText("Block objective", block.objective());
        require(block.methods() == null || block.methods().size() <= 10, "A block can have at most 10 activities.");
        if (block.sections() != null) {
            require(block.sections().size() <= 20, "A block can have at most 20 sections.");
            int steps = 0;
            for (ActivitySectionDto s : block.sections()) {
                if (s.steps() == null) continue;
                steps += s.steps().size();
                s.steps().forEach(step -> checkText("Step", step));
            }
            require(steps <= MAX_STEPS_PER_BLOCK, "A block can have at most " + MAX_STEPS_PER_BLOCK + " steps.");
        }
    }

    public void checkGoalCheck(RefineGoalRequestDto request) {
        checkText("Learning goal", request.goal());
        // The context (session type, background, sub-skills) is small; its text form bounds it
        require(request.context() == null || String.valueOf(request.context()).length() <= 4 * MAX_TEXT,
                "The goal's context is too long.");
    }

    public void checkGoalTexts(List<String> goals) {
        require(goals == null || goals.size() <= MAX_GOALS, "A session can have at most " + MAX_GOALS + " learning goals.");
        if (goals != null) goals.forEach(g -> checkText("Learning goal", g));
    }

    private void checkGoals(List<LearningGoalPlanDto> goals) {
        if (goals == null) return;
        require(goals.size() <= MAX_GOALS, "A session can have at most " + MAX_GOALS + " learning goals.");
        for (LearningGoalPlanDto g : goals) checkText("Learning goal", g.goal());
    }

    private void checkMeta(WorkshopInputDto meta) {
        if (meta == null) return;
        checkText("Title", meta.title());
        checkText("Student background", meta.studentBackground());
        require(meta.duration() >= 0 && meta.duration() <= MAX_DURATION,
                "A session can last at most " + MAX_DURATION + " minutes.");
        require(meta.selectedActivities() == null || meta.selectedActivities().size() <= MAX_ACTIVITIES,
                "At most " + MAX_ACTIVITIES + " activities can be selected.");
        if (meta.selectedActivities() != null) meta.selectedActivities().forEach(a -> checkText("Activity", a));
    }

    private static void checkText(String what, String value) {
        require(value == null || value.length() <= MAX_TEXT, what + " is too long (at most " + MAX_TEXT + " characters).");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException tooMany(String message) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    // ── Storage checks ────────────────────────────────────────────────────

    public static final int MAX_TITLE = 300;

    public static void checkTitle(String title) {
        require(title == null || title.length() <= MAX_TITLE, "The title is too long (at most " + MAX_TITLE + " characters).");
    }

    public static void checkSessionCount(long existing) {
        require(existing < MAX_SESSIONS_PER_USER, "You have reached the limit of " + MAX_SESSIONS_PER_USER + " sessions. Delete some to create new ones.");
    }

    public static void checkCourseCount(long existing) {
        require(existing < MAX_COURSES_PER_USER, "You have reached the limit of " + MAX_COURSES_PER_USER + " courses. Delete some to create new ones.");
    }

    public static void checkStoredSize(String what, String json, int max) {
        if (json != null && json.length() > max) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, what + " is too large to save.");
        }
    }
}

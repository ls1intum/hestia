package app.user;

import app.error.ApiException;
import app.user.UserDtos.QuotaResponse;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user LLM metering, replacing the previous position where the only limits
 * were global: a per-IP request filter (which cannot know who is calling,
 * because it runs before authentication) and a shared solver thread pool. Those
 * bound total load but let any one user consume the whole budget.
 *
 * <p>Two ceilings apply, and both must pass. The per-user quota is about
 * fairness — one person cannot crowd everyone else out. The instance-wide ceiling
 * is about the provider bill, and it exists because the per-user quota alone
 * bounds nothing: registration is open, so anyone can trade a used-up account for
 * a fresh allowance, and the per-IP registration cap that slows this down is only
 * as trustworthy as the proxy headers it reads. The global count is derived from
 * actual usage, so neither churning accounts nor forging a header moves it.
 *
 * <p>Counts are kept in the database rather than in memory so a restart does not
 * reset everyone's quota, and so the numbers stay correct if the service is ever
 * run as more than one instance.
 */
@Service
public class LlmQuotaService {

    public static final String KIND_PARSE = "parse";
    public static final String KIND_SOLVE = "solve";

    private final LlmUsageRepository usage;

    @Value("${app.quota.parse-per-day:20}")
    private int parsePerDay;

    @Value("${app.quota.solve-per-day:20}")
    private int solvePerDay;

    /**
     * Instance-wide daily ceilings. Set generously enough not to bite normal use;
     * they are a backstop on spend, not a fairness control.
     */
    @Value("${app.quota.global-parse-per-day:200}")
    private int globalParsePerDay;

    @Value("${app.quota.global-solve-per-day:200}")
    private int globalSolvePerDay;

    public LlmQuotaService(LlmUsageRepository usage) {
        this.usage = usage;
    }

    /**
     * Admit one job of {@code kind} for this user, or reject with 429.
     *
     * <p>Call this before dispatching to a background pool: once work is queued
     * the spend is already committed, so the check has to gate admission rather
     * than execution.
     */
    @Transactional
    public void checkAndRecord(String userId, String kind) {
        check(userId, kind);
        record(userId, kind);
    }

    /**
     * Reject with 429 if the user is at their limit, without consuming anything.
     *
     * <p>Split from {@link #record} for callers that mutate state on the way to
     * dispatch: solving resets prior answers and grades, so the refusal has to
     * happen before that rather than after, or a rejected call would leave the
     * exam wiped and mid-flight.
     */
    public void check(String userId, String kind) {
        OffsetDateTime since = windowStart();

        int limit = limitFor(kind);
        long used = usage.countSince(UUID.fromString(userId), kind, since);
        if (used >= limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                "Daily %s limit reached (%d per day). Try again tomorrow.".formatted(kind, limit));
        }

        // Distinct wording on purpose: "you are out" and "the instance is out" call
        // for different actions from the person reading it, and only the second is
        // worth telling an administrator about.
        int globalLimit = globalLimitFor(kind);
        if (usage.countAllSince(kind, since) >= globalLimit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                ("ExamLense has reached its daily %s limit across all users (%d per day). "
                    + "Try again tomorrow, or ask an administrator to raise it.")
                    .formatted(kind, globalLimit));
        }
    }

    /**
     * Consume one job. Two concurrent dispatches can both pass {@link #check}
     * and land here, overshooting the limit by one — tolerated deliberately
     * rather than serialized behind a lock, since this bounds daily spend rather
     * than enforcing a hard cap.
     */
    @Transactional
    public void record(String userId, String kind) {
        LlmUsage row = new LlmUsage();
        row.setUserId(UUID.fromString(userId));
        row.setKind(kind);
        usage.save(row);
    }

    public QuotaResponse remainingFor(String userId) {
        UUID id = UUID.fromString(userId);
        OffsetDateTime since = windowStart();
        long parseUsed = usage.countSince(id, KIND_PARSE, since);
        long solveUsed = usage.countSince(id, KIND_SOLVE, since);
        return new QuotaResponse(
            (int) Math.max(0, parsePerDay - parseUsed), parsePerDay,
            (int) Math.max(0, solvePerDay - solveUsed), solvePerDay);
    }

    /** Rolling 24h rather than calendar-day, so nobody games the reset at midnight. */
    private static OffsetDateTime windowStart() {
        return OffsetDateTime.now().minusDays(1);
    }

    private int limitFor(String kind) {
        return switch (kind) {
            case KIND_PARSE -> parsePerDay;
            case KIND_SOLVE -> solvePerDay;
            default -> throw new IllegalArgumentException("Unknown quota kind: " + kind);
        };
    }

    private int globalLimitFor(String kind) {
        return switch (kind) {
            case KIND_PARSE -> globalParsePerDay;
            case KIND_SOLVE -> globalSolvePerDay;
            default -> throw new IllegalArgumentException("Unknown quota kind: " + kind);
        };
    }
}

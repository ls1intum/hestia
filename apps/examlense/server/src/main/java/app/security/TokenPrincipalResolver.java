package app.security;

import app.user.Tokens;
import app.user.User;
import app.user.UserRepository;
import app.user.UserToken;
import app.user.UserTokenRepository;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resolves a presented bearer token to its owning user, with a short-lived
 * in-process cache.
 *
 * <p>The cache is not premature optimization: the per-IP limiter in front of us
 * admits 300 requests per 10s, and the SSE endpoints re-authenticate on every
 * async re-dispatch, so an uncached lookup would mean a Postgres round-trip on
 * essentially every request. The trade is that a revoked token keeps working for
 * up to the TTL — acceptable for interim auth, and the reason revocation is
 * documented as "takes effect within a minute" rather than instantly.
 */
@Service
public class TokenPrincipalResolver {

    /** Bound so a flood of distinct invalid tokens cannot grow the map without limit. */
    private static final int MAX_ENTRIES = 20_000;

    private final UserRepository users;
    private final UserTokenRepository tokens;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    @Value("${app.auth.principal-cache-seconds:60}")
    private long cacheSeconds;

    public TokenPrincipalResolver(UserRepository users, UserTokenRepository tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** A resolved principal, or a cached negative when {@code userId} is null. */
    public record Principal(String userId, boolean admin) {}

    private record Entry(Principal principal, long cachedAtMs) {}

    public Optional<Principal> resolve(String presentedToken) {
        String hash = Tokens.hash(presentedToken);
        long now = System.currentTimeMillis();

        Entry cached = cache.get(hash);
        if (cached != null && now - cached.cachedAtMs() < cacheSeconds * 1000L) {
            return Optional.ofNullable(cached.principal());
        }

        if (cache.size() > MAX_ENTRIES) evictExpired(now);

        Principal resolved = lookupAndTouch(hash);
        cache.put(hash, new Entry(resolved, now));
        return Optional.ofNullable(resolved);
    }

    /**
     * Runs once per cache miss. {@code last_used_at} / {@code last_seen_at} are
     * stamped here rather than per request, so the write rate is bounded by the
     * cache TTL instead of by traffic.
     *
     * <p>No {@code @Transactional} on purpose: this is self-invoked from
     * {@link #resolve}, which bypasses the Spring proxy and would make the
     * annotation silently do nothing. The two writes are each transactional in
     * their own right (the repository methods are), and the reads need no
     * atomicity with them.
     */
    private Principal lookupAndTouch(String hash) {
        Optional<UserToken> found = tokens.findByTokenHash(hash);
        if (found.isEmpty()) return null;

        UserToken token = found.get();
        if (!token.isActive()) return null;

        Optional<User> owner = users.findById(token.getUserId());
        if (owner.isEmpty()) return null;

        User user = owner.get();
        tokens.touch(token.getId());
        users.touchLastSeen(user.getId());

        return new Principal(user.getId().toString(), user.isAdmin());
    }

    /** Drop a token from the cache so a fresh revoke or admin change takes effect now. */
    public void invalidate(String presentedToken) {
        cache.remove(Tokens.hash(presentedToken));
    }

    public void invalidateAll() {
        cache.clear();
    }

    private void evictExpired(long now) {
        cache.entrySet().removeIf(e -> now - e.getValue().cachedAtMs() >= cacheSeconds * 1000L);
    }
}

package app.user;

import app.error.ApiException;
import app.user.Tokens;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts and their interim credentials.
 *
 * <p>Registration is open: a first visit creates an account with nothing asked of
 * the user, identified by a generated {@code anon-…} handle. {@link #linkExternalId}
 * then lets them attach their TUM ID whenever they like, which is what a TUM login
 * will match on once SAML replaces this scheme — so a linked account keeps its
 * exams across the cutover and an unlinked one does not.
 *
 * <p>{@link #findOrCreateByExternalId} is the single creation-by-identifier path;
 * the SAML authentication success handler will call it at cutover, so both flows
 * agree on normalization.
 */
@Service
public class UserService {

    /**
     * Suffixes TUM identities show up with. We do not yet know whether the IdP
     * releases a bare {@code ab12cde} or an eduPersonPrincipalName like
     * {@code ab12cde@tum.de}, so both sides get normalized to the bare form.
     */
    private static final String[] KNOWN_DOMAINS = {"@tum.de", "@mytum.de", "@cit.tum.de"};

    /** Namespace for generated handles; also what marks an account as not yet linked. */
    public static final String ANON_PREFIX = "anon-";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UserTokenRepository tokens;

    /** Default lifetime for a minted session token; interim auth is not meant to outlive SAML. */
    @Value("${app.auth.token-ttl-days:180}")
    private long tokenTtlDays;

    /** Off switches open registration back to "existing token holders only". */
    @Value("${app.auth.open-registration:true}")
    private boolean openRegistration;

    /**
     * Cap on new accounts per IP per day. Without it, clearing localStorage mints a
     * fresh account with a fresh LLM quota, which would make per-user metering
     * meaningless and the provider bill unbounded.
     */
    @Value("${app.auth.registrations-per-ip-per-day:5}")
    private int registrationsPerIpPerDay;

    public UserService(UserRepository users, UserTokenRepository tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /**
     * Lowercase, trim, and drop a known TUM domain suffix. Intentionally does NOT
     * reject unfamiliar shapes — guessing wrong about the format TUM releases would
     * lock a legitimate user out for no security benefit.
     */
    public static String normalizeExternalId(String raw) {
        if (raw == null) throw new ApiException(HttpStatus.BAD_REQUEST, "TUM ID is required");
        String id = raw.trim().toLowerCase();
        for (String domain : KNOWN_DOMAINS) {
            if (id.endsWith(domain)) {
                id = id.substring(0, id.length() - domain.length());
                break;
            }
        }
        if (id.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "TUM ID is required");
        return id;
    }

    /** Whether this account still has a generated handle rather than a real TUM ID. */
    public static boolean isAnonymous(User user) {
        return user.getExternalId().startsWith(ANON_PREFIX);
    }

    /**
     * Create an account for a caller who has no credential yet, and mint its token.
     *
     * @param ipHash sha256 of the caller's IP — used only to count registrations.
     */
    @Transactional
    public Registration register(String ipHash) {
        if (!openRegistration) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                "This instance is not accepting new users. Ask an administrator for access.");
        }
        long recent = users.countRegistrationsFrom(ipHash, OffsetDateTime.now().minusDays(1));
        if (recent >= registrationsPerIpPerDay) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many new accounts from this network today. Try again tomorrow, or sign in "
                    + "with an existing access key.");
        }

        User user = new User();
        user.setExternalId(generateAnonHandle());
        user.setCreatedIpHash(ipHash);
        users.save(user);
        return new Registration(user, mintToken(user.getId(), "self-registered"));
    }

    /**
     * Attach a TUM ID to an existing account, replacing its generated handle.
     *
     * <p>Refuses an identifier already held by someone else. That matters beyond
     * tidiness: this value is what a TUM login will match on, so letting two
     * accounts claim one TUM ID would hand a stranger's exams to whoever the IdP
     * resolves to first.
     */
    @Transactional
    public User linkExternalId(UUID userId, String rawExternalId) {
        String externalId = normalizeExternalId(rawExternalId);
        if (externalId.startsWith(ANON_PREFIX)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That is not a valid TUM ID");
        }

        User user = users.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Unknown user"));

        Optional<User> holder = users.findByExternalId(externalId);
        if (holder.isPresent() && !holder.get().getId().equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "That TUM ID is already linked to another account.");
        }

        user.setExternalId(externalId);
        return users.save(user);
    }

    @Transactional
    public User findOrCreateByExternalId(String rawExternalId, String displayName) {
        String externalId = normalizeExternalId(rawExternalId);
        return users.findByExternalId(externalId).orElseGet(() -> {
            User u = new User();
            u.setExternalId(externalId);
            u.setDisplayName(displayName);
            return users.save(u);
        });
    }

    /** Mint a session token for a user. The plaintext is returned here and never stored. */
    @Transactional
    public String mintToken(UUID userId, String label) {
        String plaintext = Tokens.generate();
        UserToken token = new UserToken();
        token.setUserId(userId);
        token.setTokenHash(Tokens.hash(plaintext));
        token.setLabel(label);
        token.setExpiresAt(OffsetDateTime.now().plusDays(tokenTtlDays));
        tokens.save(token);
        return plaintext;
    }

    /** Hex rather than base64url: this handle is shown to the user, and base64url
     * emits leading dashes and underscores that read like a glitch. */
    private static String generateAnonHandle() {
        byte[] raw = new byte[6];
        RANDOM.nextBytes(raw);
        StringBuilder hex = new StringBuilder(ANON_PREFIX);
        for (byte b : raw) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    public record Registration(User user, String token) {}
}

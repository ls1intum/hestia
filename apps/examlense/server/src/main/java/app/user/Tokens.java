package app.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Generation and hashing of the interim auth session tokens. */
public final class Tokens {

    /** Marks a string as one of ours in logs and support conversations. */
    public static final String PREFIX = "exl_";

    private static final int ENTROPY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    /**
     * 256 bits of entropy, base64url without padding — well past guessable, which
     * is what lets a token be the account's only credential.
     */
    public static String generate() {
        byte[] raw = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(raw);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * SHA-256 hex. A plain digest rather than a password hash on purpose: these
     * are full-entropy random strings, not human-chosen secrets, so there is no
     * dictionary to slow down — and the digest runs on every authenticated
     * request, where bcrypt's cost would be a real latency tax.
     */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

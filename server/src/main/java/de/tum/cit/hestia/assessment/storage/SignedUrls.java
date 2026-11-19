package de.tum.cit.hestia.assessment.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * HMAC-signed, time-limited file URLs — the local-FS equivalent of Supabase
 * Storage signed URLs. Lets the frontend render figures via a plain {@code <img src>}
 * (the session cookie authenticates) while keeping the bucket private: the content endpoint
 * (`GET /api/files/**`) validates the signature itself; it also needs the session login
 * and an object of the caller's organisation.
 *
 * Signing uses a dedicated secret ({@code app.files.signing-secret}); when unset a random key is
 * used, so URLs do not survive a restart.
 */
@Component
public class SignedUrls {

    private static final Logger log = LoggerFactory.getLogger(SignedUrls.class);

    private final byte[] secret;

    public SignedUrls(@Value("${app.files.signing-secret:}") String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            log.warn("app.files.signing-secret is not set - signing file URLs with a random key that "
                + "changes on every start. Set FILES_SIGNING_SECRET.");
            byte[] random = new byte[32];
            new java.security.SecureRandom().nextBytes(random);
            this.secret = random;
        } else {
            this.secret = signingSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Build `/api/files/{bucket}/{path}?exp=…&sig=…` valid for ttlSeconds. */
    public String buildUrl(String bucket, String path, long ttlSeconds) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        String sig = sign(bucket, path, exp);
        // Encode so a path with reserved characters round-trips to the same
        // decoded value the content endpoint verifies against.
        return "/api/files/" + UriUtils.encodePath(bucket, StandardCharsets.UTF_8)
            + "/" + UriUtils.encodePath(path, StandardCharsets.UTF_8)
            + "?exp=" + exp + "&sig=" + sig;
    }

    public boolean verify(String bucket, String path, long exp, String sig) {
        if (sig == null || exp < Instant.now().getEpochSecond()) return false;
        String expected = sign(bucket, path, exp);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String bucket, String path, long exp) {
        String message = bucket + "\n" + path + "\n" + exp;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign url: " + e.getMessage(), e);
        }
    }
}

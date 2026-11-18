package de.tum.cit.hestia.materials.intake;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The terms of use shown before an upload. The text lives versioned in the repository
 * ({@code intake/terms-<version>.md}); a changed text gets a new file and version. The hash lets a
 * stored acceptance be matched to the exact text that was shown.
 */
@Component
public class IntakeTerms {

    /** The terms in force: version, SHA-256 of the text (hex) and the Markdown text itself. */
    public record Current(String version, String sha256, String text) {
    }

    private final Current current;

    IntakeTerms(@Value("${hestia.intake.terms-version}") String version) {
        String path = "intake/terms-" + version + ".md";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            this.current = new Current(version, sha256(bytes), new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Terms of use not found: " + path, e);
        }
    }

    public Current current() {
        return current;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package de.tum.cit.hestia.assessment.storage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SignedUrlsTest {

    private static final Pattern URL = Pattern.compile("^/api/files/([^/]+)/(.+)\\?exp=(\\d+)&sig=([\\w-]+)$");

    private final SignedUrls signer = new SignedUrls("test-signing-secret");

    private record Parsed(String bucket, String path, long exp, String sig) {}

    private static Parsed parse(String url) {
        Matcher m = URL.matcher(url);
        assertThat(m.matches()).as("url shape: %s", url).isTrue();
        return new Parsed(m.group(1), m.group(2), Long.parseLong(m.group(3)), m.group(4));
    }

    @Test
    void builtUrlVerifies() {
        Parsed p = parse(signer.buildUrl("exam-figures", "user/exam/fig.png", 60));
        assertThat(p.exp()).isGreaterThan(Instant.now().getEpochSecond());
        assertThat(signer.verify("exam-figures", "user/exam/fig.png", p.exp(), p.sig())).isTrue();
    }

    @Test
    void tamperedPathBucketOrExpiryFailsVerification() {
        Parsed p = parse(signer.buildUrl("exam-figures", "user/exam/fig.png", 60));

        assertThat(signer.verify("exam-figures", "user/exam/OTHER.png", p.exp(), p.sig())).isFalse();
        assertThat(signer.verify("exam-pdfs", "user/exam/fig.png", p.exp(), p.sig())).isFalse();
        assertThat(signer.verify("exam-figures", "user/exam/fig.png", p.exp() + 9999, p.sig())).isFalse();
        assertThat(signer.verify("exam-figures", "user/exam/fig.png", p.exp(), "forged-sig")).isFalse();
        assertThat(signer.verify("exam-figures", "user/exam/fig.png", p.exp(), null)).isFalse();
    }

    @Test
    void expiredUrlFailsEvenWithAValidSignature() {
        // TTL in the past: the signature over (bucket, path, exp) is genuine,
        // but exp < now must still reject.
        Parsed p = parse(signer.buildUrl("exam-figures", "user/exam/fig.png", -10));
        assertThat(signer.verify("exam-figures", "user/exam/fig.png", p.exp(), p.sig())).isFalse();
    }

    @Test
    void differentSecretsDontCrossVerify() {
        SignedUrls other = new SignedUrls("another-secret");
        Parsed p = parse(signer.buildUrl("exam-figures", "fig.png", 60));
        assertThat(other.verify("exam-figures", "fig.png", p.exp(), p.sig())).isFalse();
    }

    @Test
    void withoutASigningSecretARandomKeyIsUsedPerInstance() {
        SignedUrls first = new SignedUrls("");
        SignedUrls second = new SignedUrls("");
        Parsed p = parse(first.buildUrl("exam-figures", "fig.png", 60));
        assertThat(first.verify("exam-figures", "fig.png", p.exp(), p.sig())).isTrue();
        assertThat(second.verify("exam-figures", "fig.png", p.exp(), p.sig())).isFalse();
    }
}

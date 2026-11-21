package de.tum.cit.hestia.core.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.hestia.core.internal.security.FederationMetadata.IdentityProvider;
import de.tum.cit.hestia.core.internal.security.FederationMetadata.Scope;
import de.tum.cit.hestia.core.internal.security.FederationTestKit.Idp;
import de.tum.cit.hestia.core.internal.security.FederationTestKit.Party;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/** Loading the federation metadata: only a valid signature by the federation key counts. */
class FederationMetadataTest {

    static Party federation;
    static Idp alpha;
    static Idp beta;
    static Idp hidden;

    @TempDir
    Path dir;

    private FederationMetadata loaded;

    @BeforeAll
    static void parties() {
        federation = Party.create("Test Federation Signer");
        alpha = Idp.create("https://idp.alpha.test/idp", "Universität Alpha", "alpha.test");
        beta = Idp.create("https://idp.beta.test/idp", "Beta University", "beta.test", "students.beta.test");
        Idp h = Idp.create("https://idp.hidden.test/idp", "Hidden Institute", "hidden.test");
        hidden = new Idp(h.entityId(), h.name(), h.scopes(), h.party(), true);
    }

    @AfterEach
    void stop() {
        if (loaded != null) {
            loaded.stop();
        }
    }

    private FederationMetadata load(String aggregate, Party trusted) throws Exception {
        Path metadata = Files.writeString(dir.resolve("federation.xml"), aggregate);
        Path cert = trusted.writeCertificate(dir.resolve("federation.pem"));
        loaded = new FederationMetadata(true, metadata.toUri().toString(), cert.toUri().toString(), Duration.ofHours(6),
                new DefaultResourceLoader());
        return loaded;
    }

    private static String validAggregate() {
        return FederationTestKit.aggregate(federation, Instant.now().plus(Duration.ofDays(7)), alpha, beta, hidden);
    }

    @Test
    void loadsTheIdentityProvidersOfASignedAggregate() throws Exception {
        FederationMetadata metadata = load(validAggregate(), federation);

        assertThat(metadata.identityProviders()).extracting(IdentityProvider::displayName)
                .containsExactly("Beta University", "Universität Alpha");
        IdentityProvider b = metadata.find(beta.entityId()).orElseThrow();
        assertThat(b.scopes()).extracting(Scope::value).containsExactly("beta.test", "students.beta.test");
        assertThat(b.hasScope("STUDENTS.beta.test")).isTrue();
        assertThat(b.hasScope("alpha.test")).isFalse();
        assertThat(metadata.assertingParty(alpha.entityId())).hasValueSatisfying(p -> {
            assertThat(p.getSingleSignOnServiceLocation()).isEqualTo(alpha.ssoUrl());
            assertThat(p.getVerificationX509Credentials()).hasSize(1);
        });
    }

    @Test
    void hidesProvidersThatAskForItButStillKnowsThem() throws Exception {
        FederationMetadata metadata = load(validAggregate(), federation);

        assertThat(metadata.identityProviders()).noneMatch(p -> p.entityId().equals(hidden.entityId()));
        assertThat(metadata.find(hidden.entityId())).isPresent();
    }

    @Test
    void searchesByAnyNameAndTheEntityId() throws Exception {
        FederationMetadata metadata = load(validAggregate(), federation);

        assertThat(metadata.identityProviders().stream().filter(p -> p.matches("alpha (EN)")))
                .extracting(IdentityProvider::entityId).containsExactly(alpha.entityId());
        assertThat(metadata.identityProviders().stream().filter(p -> p.matches("idp.beta")))
                .extracting(IdentityProvider::entityId).containsExactly(beta.entityId());
    }

    @Test
    void rejectsAnAggregateSignedByAnotherKey() throws Exception {
        String forged = FederationTestKit.aggregate(Party.create("Someone Else"), Instant.now().plus(Duration.ofDays(7)),
                alpha, beta);

        assertThat(load(forged, federation).identityProviders()).isEmpty();
        assertThat(loaded.assertingParty(alpha.entityId())).isEmpty();
    }

    @Test
    void rejectsATamperedAggregate() throws Exception {
        String tampered = validAggregate().replace("alpha.test</shibmd:Scope>", "beta.test</shibmd:Scope>");

        assertThat(load(tampered, federation).find(alpha.entityId())).isEmpty();
    }

    @Test
    void rejectsAnUnsignedAggregate() throws Exception {
        String unsigned = validAggregate().replaceAll("(?s)<ds:Signature.*?</ds:Signature>", "");

        assertThat(load(unsigned, federation).identityProviders()).isEmpty();
    }

    @Test
    void rejectsExpiredAndOverlyLongValidMetadata() throws Exception {
        String expired = FederationTestKit.aggregate(federation, Instant.now().minus(Duration.ofHours(1)), alpha);
        assertThat(load(expired, federation).identityProviders()).isEmpty();
        loaded.stop();

        String forever = FederationTestKit.aggregate(federation, Instant.now().plus(Duration.ofDays(365)), alpha);
        assertThat(load(forever, federation).identityProviders()).isEmpty();
    }

    @Test
    void isOffByDefaultAndNeedsBothLocationsWhenOn() {
        FederationMetadata off = new FederationMetadata(false, "", "", Duration.ofHours(6), new DefaultResourceLoader());
        assertThat(off.enabled()).isFalse();
        assertThat(off.identityProviders()).isEmpty();

        assertThatThrownBy(() -> new FederationMetadata(true, "", "", Duration.ofHours(6), new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theShippedDfnCertificateIsReadable() throws Exception {
        var cert = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new DefaultResourceLoader().getResource("classpath:federation/dfn-aai.pem").getInputStream());
        assertThat(cert.getSubjectX500Principal().getName()).contains("DFN-AAI Metadatasigner");
        assertThat(List.of(java.util.HexFormat.ofDelimiter(":").withUpperCase().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))))
                .containsExactly("6E:FE:08:72:C3:C2:49:77:AF:67:31:F3:AC:30:F7:17:17:03:EF:FA:BB:6C:88:14:97:B3:E1:A5:DD:A5:3A:A1");
    }
}

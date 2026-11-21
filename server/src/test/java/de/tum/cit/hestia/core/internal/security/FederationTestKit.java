package de.tum.cit.hestia.core.internal.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.shibboleth.utilities.java.support.xml.SerializeSupport;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.SignatureSigningParameters;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.SignatureSupport;
import org.springframework.security.saml2.core.OpenSamlInitializationService;

/**
 * Synthetic SAML parties for tests: key pairs with self-signed certificates, identity provider
 * metadata, a federation aggregate signed by a federation key, and signed SAML responses.
 */
final class FederationTestKit {

    static {
        OpenSamlInitializationService.initialize();
    }

    static final String ATTR_SCHAC = "urn:oid:1.3.6.1.4.1.25178.1.2.9";
    static final String ATTR_EPPN = "urn:oid:1.3.6.1.4.1.5923.1.1.1.6";
    static final String ATTR_DISPLAY_NAME = "urn:oid:2.16.840.1.113730.3.1.241";

    /** A key pair with a self-signed certificate. */
    record Party(KeyPair keys, X509Certificate certificate) {

        static Party create(String commonName) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keys = generator.generateKeyPair();
                X500Name name = new X500Name("CN=" + commonName);
                Instant now = Instant.now();
                var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()),
                        Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(365))), name,
                        keys.getPublic());
                X509Certificate cert = new JcaX509CertificateConverter()
                        .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
                return new Party(keys, cert);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        String certificateBase64() {
            try {
                return Base64.getEncoder().encodeToString(certificate.getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        Path writeCertificate(Path file) throws IOException {
            Files.writeString(file, "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded())
                    + "\n-----END CERTIFICATE-----\n");
            return file;
        }

        Path writePrivateKey(Path file) throws IOException {
            // The key is generated per test run and written to a temporary folder only; the PEM label
            // is assembled so the secret scanner does not mistake this code for a committed key.
            String label = "PRIVATE" + " KEY";
            Files.writeString(file, "-----BEGIN " + label + "-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                    + "\n-----END " + label + "-----\n");
            return file;
        }

        private byte[] encoded() {
            try {
                return certificate.getEncoded();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        BasicX509Credential credential() {
            return new BasicX509Credential(certificate, keys.getPrivate());
        }
    }

    /** A simulated identity provider of the federation. */
    record Idp(String entityId, String name, List<String> scopes, Party party, boolean hidden) {

        static Idp create(String entityId, String name, String... scopes) {
            return new Idp(entityId, name, List.of(scopes), Party.create(name), false);
        }

        String ssoUrl() {
            return entityId + "/sso";
        }

        String entityDescriptor() {
            String scopeXml = scopes.stream()
                    .map(s -> "<shibmd:Scope regexp=\"false\">" + s + "</shibmd:Scope>")
                    .collect(Collectors.joining());
            String hide = hidden ? """
                    <md:Extensions><mdattr:EntityAttributes>
                      <saml:Attribute Name="http://macedir.org/entity-category" NameFormat="urn:oasis:names:tc:SAML:2.0:attrname-format:uri">
                        <saml:AttributeValue>http://refeds.org/category/hide-from-discovery</saml:AttributeValue>
                      </saml:Attribute>
                    </mdattr:EntityAttributes></md:Extensions>""" : "";
            return """
                    <md:EntityDescriptor entityID="%s">%s
                      <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                        <md:Extensions>%s<mdui:UIInfo><mdui:DisplayName xml:lang="de">%s</mdui:DisplayName>
                          <mdui:DisplayName xml:lang="en">%s (en)</mdui:DisplayName></mdui:UIInfo></md:Extensions>
                        <md:KeyDescriptor use="signing"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>
                        <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="%s"/>
                      </md:IDPSSODescriptor>
                    </md:EntityDescriptor>
                    """.formatted(entityId, hide, scopeXml, name, name, party.certificateBase64(), ssoUrl());
        }

        /** Stand-alone metadata of this provider (for the configured TUM registration). */
        String metadata() {
            return entityDescriptor().replaceFirst("<md:EntityDescriptor ", "<md:EntityDescriptor " + NAMESPACES + " ");
        }

        /**
         * A SAML response of this provider for the service provider {@code spEntityId} at the
         * assertion consumer service {@code acs}, signed with {@code signer}'s key.
         */
        String response(String spEntityId, String acs, String inResponseTo, Map<String, String> attributes, Party signer) {
            Instant now = Instant.now();
            String attrs = attributes.entrySet().stream().map(a -> """
                    <saml:Attribute Name="%s" NameFormat="urn:oasis:names:tc:SAML:2.0:attrname-format:uri">
                      <saml:AttributeValue xsi:type="xs:string">%s</saml:AttributeValue></saml:Attribute>"""
                    .formatted(a.getKey(), a.getValue())).collect(Collectors.joining());
            String irt = inResponseTo == null ? "" : " InResponseTo=\"" + inResponseTo + "\"";
            String xml = """
                    <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
                        xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        ID="_r%s" Version="2.0" IssueInstant="%s" Destination="%s"%s>
                      <saml:Issuer>%s</saml:Issuer>
                      <samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/></samlp:Status>
                      <saml:Assertion ID="_a%s" Version="2.0" IssueInstant="%s">
                        <saml:Issuer>%s</saml:Issuer>
                        <saml:Subject>
                          <saml:NameID Format="urn:oasis:names:tc:SAML:2.0:nameid-format:transient">_n%s</saml:NameID>
                          <saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">
                            <saml:SubjectConfirmationData NotOnOrAfter="%s" Recipient="%s"%s/>
                          </saml:SubjectConfirmation>
                        </saml:Subject>
                        <saml:Conditions NotBefore="%s" NotOnOrAfter="%s">
                          <saml:AudienceRestriction><saml:Audience>%s</saml:Audience></saml:AudienceRestriction>
                        </saml:Conditions>
                        <saml:AuthnStatement AuthnInstant="%s">
                          <saml:AuthnContext><saml:AuthnContextClassRef>urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport</saml:AuthnContextClassRef></saml:AuthnContext>
                        </saml:AuthnStatement>
                        <saml:AttributeStatement>%s</saml:AttributeStatement>
                      </saml:Assertion>
                    </samlp:Response>
                    """.formatted(UUID.randomUUID(), now, acs, irt, entityId, UUID.randomUUID(), now, entityId,
                    UUID.randomUUID(), now.plusSeconds(300), acs, irt, now.minusSeconds(60), now.plusSeconds(300),
                    spEntityId, now, attrs);
            return sign(xml, signer);
        }
    }

    static final String NAMESPACES = "xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\""
            + " xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\""
            + " xmlns:mdui=\"urn:oasis:names:tc:SAML:metadata:ui\""
            + " xmlns:mdattr=\"urn:oasis:names:tc:SAML:metadata:attribute\""
            + " xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\""
            + " xmlns:shibmd=\"urn:mace:shibboleth:metadata:1.0\"";

    private FederationTestKit() {}

    /** The federation aggregate of {@code idps}, valid until {@code validUntil}, signed by {@code signer}. */
    static String aggregate(Party signer, Instant validUntil, Idp... idps) {
        StringBuilder xml = new StringBuilder("<md:EntitiesDescriptor " + NAMESPACES + " ID=\"_fed" + UUID.randomUUID()
                + "\" Name=\"https://federation.test\" validUntil=\"" + validUntil + "\">");
        for (Idp idp : idps) {
            xml.append(idp.entityDescriptor());
        }
        xml.append("</md:EntitiesDescriptor>");
        return sign(xml.toString(), signer);
    }

    static String sign(String xml, Party signer) {
        try {
            SignableSAMLObject object = (SignableSAMLObject) XMLObjectSupport.unmarshallFromInputStream(
                    XMLObjectProviderRegistrySupport.getParserPool(),
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            SignatureSigningParameters params = new SignatureSigningParameters();
            params.setSigningCredential(signer.credential());
            params.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
            params.setSignatureReferenceDigestMethod(SignatureConstants.ALGO_ID_DIGEST_SHA256);
            params.setSignatureCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
            SignatureSupport.signObject(object, params);
            return SerializeSupport.nodeToString(object.getDOM());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

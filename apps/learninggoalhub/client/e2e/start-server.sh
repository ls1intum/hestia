#!/usr/bin/env bash
# Starts the built Hestia server jar for the browser tests: against the fake model on FAKE_LLM_PORT
# and the Postgres on E2E_POSTGRES_PORT (database hestia with the roles from
# infra/postgres/init/01-roles.sql), with a throwaway SAML key pair (the server refuses to start
# without one). Build the jar first: ./gradlew :server:bootJar
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
jar="$here/../../../../server/build/libs/hestia-server.jar"
keys="$(mktemp -d)"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=localhost" \
  -keyout "$keys/saml-key.pem" -out "$keys/saml-cert.crt" 2>/dev/null

export SERVER_PORT="${E2E_SERVER_PORT:-8090}"
export POSTGRES_PORT="${E2E_POSTGRES_PORT:-5433}"
# The role passwords the database was set up with; the defaults match compose.yaml.
export HESTIA_OWNER_PASSWORD="${HESTIA_OWNER_PASSWORD:-hestia_owner}"
export HESTIA_APP_PASSWORD="${HESTIA_APP_PASSWORD:-hestia_app}"
export LOGOS_API_KEY=fake
export SPRING_AI_OPENAI_BASE_URL="http://localhost:${FAKE_LLM_PORT:-4010}"
# One skill per 40 characters, so the short fixture lines may each become a skill.
export HESTIA_EXTRACTION_SKILL_TARGET_CHARS=40
export SAML_PRIVATE_KEY_LOCATION="file:$keys/saml-key.pem"
export SAML_CERTIFICATE_LOCATION="file:$keys/saml-cert.crt"
# The identity provider comes only from configuration; the browser tests never log in through
# it (the local profile does), but the server reads its metadata at startup. A throwaway one:
cert_body="$(sed '/-----/d' "$keys/saml-cert.crt" | tr -d '
')"
cat > "$keys/idp-metadata.xml" <<XML
<EntityDescriptor xmlns="urn:oasis:names:tc:SAML:2.0:metadata" entityID="https://idp.e2e.invalid">
  <IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
    <KeyDescriptor use="signing">
      <KeyInfo xmlns="http://www.w3.org/2000/09/xmldsig#"><X509Data><X509Certificate>$cert_body</X509Certificate></X509Data></KeyInfo>
    </KeyDescriptor>
    <SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="https://idp.e2e.invalid/sso"/>
  </IDPSSODescriptor>
</EntityDescriptor>
XML
export SAML_IDP_ENTITY_ID="https://idp.e2e.invalid"
export SAML_IDP_METADATA_URI="file:$keys/idp-metadata.xml"
# /api/** needs a login; the local profile signs every request in as a dev user. The privacy test
# picks a fresh user with the X-Dev-User header (impersonation), so it sees the first-upload dialog.
export SPRING_PROFILES_ACTIVE=local
export HESTIA_DEV_LOGIN_IMPERSONATION=true
exec java -jar "$jar"

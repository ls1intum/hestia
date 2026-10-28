#!/usr/bin/env bash
# Starts the built server jar for the browser tests: against the fake model on FAKE_LLM_PORT and
# the Postgres on E2E_POSTGRES_PORT, with a throwaway SAML key pair (the server refuses to start
# without one). Build the jar first: ./gradlew :apps:learninggoalhub:server:bootJar
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
jar="$here/../../server/build/libs/learninggoalhub-server.jar"
keys="$(mktemp -d)"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=localhost" \
  -keyout "$keys/saml-key.pem" -out "$keys/saml-cert.crt" 2>/dev/null

export SERVER_PORT="${E2E_SERVER_PORT:-8090}"
export POSTGRES_PORT="${E2E_POSTGRES_PORT:-5433}"
export LOGOS_API_KEY=fake
export SPRING_AI_OPENAI_BASE_URL="http://localhost:${FAKE_LLM_PORT:-4010}"
# One skill per 40 characters, so the short fixture lines may each become a skill.
export HESTIA_EXTRACTION_SKILL_TARGET_CHARS=40
export SAML_PRIVATE_KEY_LOCATION="file:$keys/saml-key.pem"
export SAML_CERTIFICATE_LOCATION="file:$keys/saml-cert.crt"
exec java -jar "$jar"

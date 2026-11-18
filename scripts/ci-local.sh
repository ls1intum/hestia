#!/usr/bin/env bash
# Runs locally the checks of the hestia CI/CD workflow that gate the image build, so a pull request
# is not the first place they run. Run it before every push.
#
#   scripts/ci-local.sh          deploy preflight, server + shared library tests, client tests
#   scripts/ci-local.sh --e2e    additionally the LearningGoalHub browser tests
#
# Needs: Java 21, Node 22, Docker (Testcontainers starts Postgres for the server tests).
# --e2e additionally needs Postgres on localhost:5433 with the roles from
# infra/postgres/init/01-roles.sql, as the test-e2e job sets it up.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

e2e=0
for arg in "$@"; do
  case "$arg" in
    --e2e) e2e=1 ;;
    *) echo "usage: $0 [--e2e]" >&2; exit 2 ;;
  esac
done

# Makes the client tools behave as on the runner (no watch mode, no interactive prompts).
export CI=true

step() { printf '\n==> %s\n' "$*"; }

step "Deploy preflight"
scripts/deploy-preflight.sh

step "Server and shared library tests (./gradlew testAll)"
./gradlew --no-daemon testAll

for app in examlense learninggoalhub workshopper; do
  step "Client tests: $app"
  (
    cd "apps/$app/client"
    npm ci --no-audit --no-fund
    if [ "$app" = learninggoalhub ]; then
      npm run lint
      npm run typecheck
    fi
    npm test
  )
done

if [ "$e2e" -eq 1 ]; then
  step "LearningGoalHub browser tests"
  ./gradlew --no-daemon :server:bootJar
  (cd apps/learninggoalhub/client && npx playwright install chromium && npm run e2e)
fi

printf '\nAll local CI checks passed.\n'

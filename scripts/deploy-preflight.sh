#!/usr/bin/env bash
# Deploy preflight: rebuilds on this machine the file layout that the Staging deploy creates on the
# VM and checks the compose file there. The deploy itself only runs after a merge to main, so
# without this check a broken deploy configuration shows up only once it is already on main.
#
# What the deploy does (see .github/workflows/hestia-cicd.yml and the ls1intum
# deploy-docker-compose reusable workflow):
#   - copies the `docker-compose-file` to the repository root under its basename, then to the VM
#     base path. A compose file that already sits at the root makes that copy fail.
#   - the copy-db-init job copies each `source:` file to the VM base path, keeping its relative path.
#   - writes a .env with ENVIRONMENT, IMAGE_TAG and the GitHub environment's secrets and variables.
#
# Checks:
#   1. the compose file named in the workflow exists and is not at the repository root
#   2. every relative bind mount in it exists in the VM layout
#   3. `docker compose config` accepts the file (skipped locally when Docker is not installed;
#      required in CI)
#
# Usage: scripts/deploy-preflight.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

workflow=.github/workflows/hestia-cicd.yml
fail() { echo "deploy preflight: $*" >&2; exit 1; }

compose=$(sed -n 's/^[[:space:]]*docker-compose-file:[[:space:]]*\([^[:space:]#]*\).*/\1/p' "$workflow" | head -n 1)
[ -n "$compose" ] || fail "no docker-compose-file found in $workflow"
[ -f "$compose" ] || fail "$compose (docker-compose-file in $workflow) does not exist"
[ "$(dirname "$compose")" != "." ] ||
  fail "$compose sits at the repository root; the deploy reusable copies it there first and fails. Move it into a folder (e.g. infra/)."

# Rebuild the VM base path in a temporary folder.
vm=$(mktemp -d)
trap 'rm -rf "$vm"' EXIT
cp "$compose" "$vm/$(basename "$compose")"
sed -n 's/^[[:space:]]*source:[[:space:]]*\([^[:space:]#]*\).*/\1/p' "$workflow" | while read -r src; do
  [ -e "$src" ] || fail "$src (a source: copied to the VM in $workflow) does not exist"
  mkdir -p "$vm/$(dirname "$src")"
  cp -R "$src" "$vm/$src"
done

# 2. Relative bind mounts (`- ./path:/container/path`) must exist next to the compose file on the VM.
missing=0
while read -r mount; do
  if [ ! -e "$vm/$mount" ]; then
    echo "deploy preflight: bind mount ./$mount does not exist on the VM (nothing copies it there)" >&2
    missing=1
  fi
done < <(sed -n 's/^[[:space:]]*-[[:space:]]*"\{0,1\}\.\/\([^:"]*\):.*/\1/p' "$compose")
[ "$missing" -eq 0 ] || exit 1

# 3. docker compose config, with placeholder values for every variable the file requires.
if ! docker compose version >/dev/null 2>&1; then
  [ -z "${CI:-}" ] || fail "docker compose is not available on the CI runner"
  echo "deploy preflight: Docker not found, skipping 'docker compose config' (CI runs it)"
  echo "deploy preflight: OK ($compose)"
  exit 0
fi
{
  echo "ENVIRONMENT=Staging"
  echo "IMAGE_TAG=preflight"
  echo "APP_HOST=preflight.invalid"
  grep -o '\${[A-Z0-9_]*:?' "$compose" | sed 's/^\${\(.*\):?$/\1=preflight/' | sort -u
} > "$vm/.env"
docker compose -f "$vm/$(basename "$compose")" --env-file "$vm/.env" config --quiet ||
  fail "docker compose config rejected $compose"

echo "deploy preflight: OK ($compose)"

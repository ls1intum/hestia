#!/usr/bin/env bash
# Proves that a backup dump can be restored: restores it into a disposable Postgres container,
# prints the row count of every table and the Flyway version, then starts the server image against
# the restored copy and checks that it turns healthy and serves the courses. The live database is
# never touched, and every container this script starts is removed again.
#
#   scripts/verify-backup.sh <dump> [server-image]
#
# server-image defaults to ghcr.io/ls1intum/hestia/learninggoalhub/server:latest. Pass "none" to
# check the restore only. On the VM, prefix with sudo (docker needs it there).
set -euo pipefail

dump="${1:?usage: verify-backup.sh <dump> [server-image|none]}"
image="${2:-ghcr.io/ls1intum/hestia/learninggoalhub/server:latest}"
[ -r "$dump" ] || { echo "cannot read $dump" >&2; exit 1; }

id="lgh-restore-check-$$"
work="$(mktemp -d)"
cleanup() {
  docker rm -f "$id-server" "$id-db" >/dev/null 2>&1 || true
  docker network rm "$id" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

docker network create "$id" >/dev/null
docker run -d --name "$id-db" --network "$id" \
  -e POSTGRES_DB=learninggoalhub -e POSTGRES_USER=learninggoalhub -e POSTGRES_PASSWORD=restore-check \
  pgvector/pgvector:pg16 >/dev/null
for _ in $(seq 1 30); do
  docker exec "$id-db" pg_isready -U learninggoalhub -d learninggoalhub >/dev/null 2>&1 && break
  sleep 2
done

psql() { docker exec -i "$id-db" psql -U learninggoalhub -d learninggoalhub -v ON_ERROR_STOP=1 -At "$@"; }

echo "== restore $dump"
docker exec -i "$id-db" pg_restore -U learninggoalhub -d learninggoalhub --no-owner --exit-on-error < "$dump"

echo "== rows per table"
psql -F $'\t' <<'SQL'
SELECT table_name,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM public.%I', table_name),
                                            false, true, '')))[1]::text::bigint
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;
SQL
echo "== flyway version: $(psql -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
courses="$(psql -c "SELECT count(*) FROM course")"

[ "$image" = none ] && { echo "restore OK (server not started)"; exit 0; }

echo "== start $image against the restored copy"
# The server refuses to start without a SAML key pair; a throwaway one is enough here.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=restore-check" \
  -keyout "$work/private-key.pem" -out "$work/certificate.crt" 2>/dev/null
chmod 644 "$work/private-key.pem"
docker run -d --name "$id-server" --network "$id" \
  -e POSTGRES_HOST="$id-db" -e POSTGRES_PASSWORD=restore-check -e LOGOS_API_KEY=restore-check \
  -e SAML_PRIVATE_KEY_LOCATION=file:/app/certs/private-key.pem \
  -e SAML_CERTIFICATE_LOCATION=file:/app/certs/certificate.crt \
  -e JAVA_OPTS=-XX:MaxRAMPercentage=70 -m 1500m \
  -v "$work:/app/certs:ro" "$image" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$id-server" curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1 && break
  if [ "$(docker inspect -f '{{.State.Running}}' "$id-server")" != true ]; then break; fi
  sleep 5
done
if ! docker exec "$id-server" curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
  echo "server did not turn healthy on the restored copy:" >&2
  docker logs --tail 80 "$id-server" >&2
  exit 1
fi
echo "== flyway version after server start: $(psql -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
served="$(docker exec "$id-server" curl -fsS "http://localhost:8080/api/courses?size=1" \
  | sed -n 's/.*"totalElements":\([0-9]*\).*/\1/p')"
echo "== /api/courses reports $served courses, the database holds $courses"
[ "$served" = "$courses" ] || { echo "course counts differ" >&2; exit 1; }
echo "restore OK"

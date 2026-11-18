#!/usr/bin/env bash
# Exports the material intake (materials.intake_submission / intake_file) from the deployed database
# into a folder: one sub-folder per submission with its files and a submission.json holding who
# submitted it, what they accepted (terms version and hash, consents) and when.
#
# There is deliberately no web page for this: only someone with shell access to the VM can read
# other people's submissions. Run it on the VM, then copy the folder off with scp.
#
#   sudo infra/scripts/export-intake.sh [target-dir] [since]
#
#   target-dir  default: ./intake-export-<UTC timestamp>
#   since       only submissions created at or after this date, e.g. 2026-12-01 (default: all)
#
# Needs: the running container hestia-postgres (infra/compose.prod.yaml), base64.
set -euo pipefail

TARGET="${1:-./intake-export-$(date -u +%Y%m%dT%H%M%SZ)}"
SINCE="${2:-1970-01-01}"
CONTAINER="${HESTIA_POSTGRES_CONTAINER:-hestia-postgres}"

if ! [[ "$SINCE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "since must look like 2026-12-01" >&2
  exit 2
fi

psql() {
  docker exec "$CONTAINER" psql -U postgres -d hestia -v ON_ERROR_STOP=1 -At "$@"
}

umask 077
mkdir -p "$TARGET"

count=0
while IFS='|' read -r submission folder; do
  [ -n "$submission" ] || continue
  dir="$TARGET/$folder"
  mkdir -p "$dir"
  psql -c "select json_build_object(
              'id', id, 'submitter_id', submitter_id, 'course_title', course_title,
              'contact_email', contact_email, 'note', note,
              'terms_version', terms_version, 'terms_sha256', terms_sha256,
              'confirmed_terms', confirmed_terms, 'consent_research', consent_research,
              'consent_replication_package', consent_replication_package, 'created_at', created_at,
              'files', (select coalesce(json_agg(json_build_object(
                          'name', original_name, 'size_bytes', size_bytes, 'sha256', sha256)
                          order by original_name), '[]'::json)
                        from materials.intake_file where submission_id = s.id))
            from materials.intake_submission s where id = '$submission'" > "$dir/submission.json"

  while IFS='|' read -r file name; do
    [ -n "$file" ] || continue
    # Keep the original name, minus anything that could leave the folder.
    safe="$(printf '%s' "$name" | tr '/\\' '__' | sed 's/^\.*//')"
    [ -n "$safe" ] || safe="$file"
    psql -c "select encode(bytes, 'base64') from materials.intake_file where id = '$file'" \
      | base64 -d > "$dir/$safe"
  done < <(psql -c "select id, original_name from materials.intake_file
                    where submission_id = '$submission' order by original_name")
  count=$((count + 1))
done < <(psql -c "select id, to_char(created_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24MISS') || '_' || left(id::text, 8)
                  from materials.intake_submission
                  where created_at >= '$SINCE'::date order by created_at")

echo "Exported $count submission(s) to $TARGET"

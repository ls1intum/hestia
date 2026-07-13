#!/usr/bin/env bash
# One-off: copy exam *content rows* from the old Supabase Postgres into the
# self-hosted Postgres. Run this WHILE the Supabase project is still alive
# (i.e. before you delete it in the dashboard).
#
# No local psql/pg_dump needed — everything runs through Docker (postgres:16)
# and the running `examlense-postgres` container.
#
# Prereqs:
#   docker compose up -d postgres            # target DB must be up
#
# Usage (default = dump from Supabase, then restore into the local container):
#   export SUPABASE_DB_URL='postgresql://postgres:<PW>@db.<REF>.supabase.co:5432/postgres'
#   ./import-rows.sh
#
# Split dump/restore across machines (e.g. laptop reaches Supabase, DB lives on a VM):
#   # on the laptop:
#   export SUPABASE_DB_URL='...'
#   ./import-rows.sh --dump-only --keep-existing         # writes /tmp/examlense-rows.sql
#   scp /tmp/examlense-rows.sql user@vm:~
#   # on the VM (docker needs sudo there; -E keeps the env):
#   sudo -E ./import-rows.sh --restore-only ~/examlense-rows.sql
#
# Get SUPABASE_DB_URL from: Supabase Dashboard → Project Settings → Database →
# Connection string → URI. Use the DIRECT connection (port 5432), not the pooler.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./import-rows.sh [options]

Copies exam content rows from Supabase into the self-hosted Postgres.
Default: dump from Supabase, then restore into the target container.

Options:
  --dump-only          Dump from Supabase to the SQL file, then stop (no restore).
                       Use to produce a file you ship to another host (e.g. a VM).
  --restore-only FILE  Skip the Supabase dump; restore FILE into the target
                       container and flatten ownership. Run this on the VM.
  --keep-existing      Additive import: emit INSERT ... ON CONFLICT DO NOTHING so
                       rows already present in the target are KEPT and never
                       overwritten — nothing existing is lost, only new rows are
                       added. Affects the dump, so pass it with the default run or
                       with --dump-only (the restore then inherits it from the file).
  -h, --help           Show this help.

Env: SUPABASE_DB_URL (required unless --restore-only), PG_IMAGE, PG_CONTAINER,
     PG_USER, PG_DB, DEFAULT_USER_ID, DUMP_FILE.
EOF
}

DUMP_ONLY=0
RESTORE_ONLY=""
KEEP_EXISTING=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dump-only)     DUMP_ONLY=1; shift ;;
    --restore-only)  RESTORE_ONLY="${2:?--restore-only needs a FILE path}"; shift 2 ;;
    --keep-existing) KEEP_EXISTING=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

PG_IMAGE="${PG_IMAGE:-postgres:16}"          # bump to match Supabase's server major if pg_dump complains
PG_CONTAINER="${PG_CONTAINER:-examlense-postgres}"
PG_USER="${PG_USER:-examlense}"
PG_DB="${PG_DB:-examlense}"
DEFAULT_USER_ID="${DEFAULT_USER_ID:-00000000-0000-0000-0000-000000000001}"
DUMP="${DUMP_FILE:-/tmp/examlense-rows.sql}"

# Content tables. FK + AFTER-INSERT triggers are disabled during load via
# --disable-triggers, so insertion order doesn't matter AND the original exam
# statuses are preserved (the maybe_finalize_evaluation trigger won't fire).
# Append: feedback parse_survey parse_metrics   # if you also want admin data.
TABLES=(exams sections section_blocks section_figures tasks task_answers task_grades)
table_args=(); for t in "${TABLES[@]}"; do table_args+=(--table="public.$t"); done

# Restore a dump file into the target container, then flatten ownership. The
# flatten only sets owner_id to the single default user (idempotent in the
# single-user model) — it never deletes rows.
restore() {
  local file="$1"
  [[ -f "$file" ]] || { echo "No such file: $file" >&2; exit 1; }

  echo "==> Restoring '$file' into container '$PG_CONTAINER' (db=$PG_DB)…"
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" \
    --set ON_ERROR_STOP=1 --single-transaction < "$file"

  echo "==> Flattening ownership onto the single default user…"
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" --set ON_ERROR_STOP=1 \
    -c "update public.exams set owner_id = '$DEFAULT_USER_ID';"
  # Optional, if you imported them:
  #   ... -c "update public.task_grades set graded_by = '$DEFAULT_USER_ID' where graded_by is not null;"
  #   ... -c "update public.feedback set user_id = '$DEFAULT_USER_ID';"
  #   ... -c "update public.parse_survey set user_id = '$DEFAULT_USER_ID';"

  echo "==> Done. Counts:"
  docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "select 'exams='   || count(*) from public.exams
     union all select 'sections='|| count(*) from public.sections
     union all select 'tasks='   || count(*) from public.tasks
     union all select 'figures=' || count(*) from public.section_figures;"
}

# --- restore-only: skip the dump entirely (e.g. run this on the VM) ---
if [[ -n "$RESTORE_ONLY" ]]; then
  restore "$RESTORE_ONLY"
  exit 0
fi

# --- dump from Supabase ---
: "${SUPABASE_DB_URL:?Set SUPABASE_DB_URL to the Supabase direct connection string (see header).}"

conflict_args=()
if [[ "$KEEP_EXISTING" -eq 1 ]]; then
  # Requires --inserts/--column-inserts (we use the latter). Any row whose PK
  # already exists in the target is silently skipped; existing data is untouched.
  conflict_args+=(--on-conflict-do-nothing)
  echo "==> --keep-existing: existing target rows are preserved (INSERT ... ON CONFLICT DO NOTHING)."
fi

echo "==> Dumping ${#TABLES[@]} tables from Supabase…"
docker run --rm "$PG_IMAGE" pg_dump "$SUPABASE_DB_URL" \
  --data-only --column-inserts --disable-triggers --no-owner --no-privileges \
  "${conflict_args[@]}" "${table_args[@]}" > "$DUMP"
echo "    wrote $DUMP ($(wc -l < "$DUMP" | tr -d ' ') lines)"

# Strip GUCs the (newer) source server emits that an older target rejects.
# PG17 adds `transaction_timeout`; a PG16 target errors on it under
# --single-transaction. Drop the line (portably, no in-place sed).
if grep -q '^SET transaction_timeout' "$DUMP"; then
  grep -v '^SET transaction_timeout' "$DUMP" > "$DUMP.tmp" && mv "$DUMP.tmp" "$DUMP"
  echo "    stripped PG17-only 'SET transaction_timeout' for the older target"
fi

if [[ "$DUMP_ONLY" -eq 1 ]]; then
  echo "==> --dump-only: skipping restore. Ship the dump to the target and restore there:"
  echo "    scp $DUMP user@host:~"
  echo "    sudo -E ./import-rows.sh --restore-only ~/$(basename "$DUMP")   # PG_CONTAINER/PG_USER/PG_DB via env if non-default"
  exit 0
fi

restore "$DUMP"
echo "Next: ./import-storage.mjs  (copies the PDF/figure files)"

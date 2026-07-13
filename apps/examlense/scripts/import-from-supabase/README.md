# One-off Supabase → self-hosted import

Copies existing exams (rows + stored files) from the old Supabase project into
the new Spring Boot + PostgreSQL stack. **Run while the Supabase project is
still alive** — before you delete it in the dashboard.

Two independent steps; run rows first, then files.

## 0. Prereqs
- `docker compose up -d postgres` (target DB up) and, ideally, the new stack
  empty (a fresh import expects no id collisions). Stop the backend first if it
  might write concurrently.
- Two credentials from the Supabase Dashboard:
  - **Direct DB URL** — Project Settings → Database → Connection string → URI
    (the **direct** one, port 5432, not the pooler).
  - **Service-role key** + **Project URL** — Project Settings → API.

## 1. Rows
```bash
export SUPABASE_DB_URL='postgresql://postgres:<PW>@db.<REF>.supabase.co:5432/postgres'
./import-rows.sh
```
Dumps the content tables (`exams, sections, section_blocks, section_figures,
tasks, task_answers, task_grades`) with `pg_dump --data-only --column-inserts
--disable-triggers`, restores them into the `examlense-postgres` container
(UUID primary keys preserved, so every FK survives), then flattens
`exams.owner_id` onto the single default user
(`00000000-0000-0000-0000-000000000001`).

- Triggers are disabled during load, so insertion order is irrelevant and the
  original exam statuses are kept intact.
- If `pg_dump` complains the server is newer than the client, set
  `PG_IMAGE=postgres:17` (match Supabase's major version).
- To include admin data, add `feedback parse_survey parse_metrics` to the
  `TABLES` list in `import-rows.sh` and uncomment the matching ownership updates.

### Flags

| Flag | What it does |
| --- | --- |
| `--keep-existing` | **Additive, non-destructive import.** Emits `INSERT … ON CONFLICT DO NOTHING`, so any row already in the target is kept untouched and only new rows are added. Without it, a PK collision aborts the whole transaction (nothing is lost, but the import fails). |
| `--dump-only` | Dump from Supabase to the SQL file, then stop — no restore. Produces a file to ship elsewhere. |
| `--restore-only FILE` | Skip the Supabase dump; restore `FILE` into the target container + flatten ownership. Run this where the DB lives. |

### Deploying to a remote VM (laptop → VM)

When Supabase is reachable from your laptop but the target DB runs on a VM (and
`docker` there needs `sudo`), split the two halves:

```bash
# On the laptop (has network access to Supabase):
export SUPABASE_DB_URL='postgresql://postgres:<PW>@db.<REF>.supabase.co:5432/postgres'
./import-rows.sh --dump-only --keep-existing          # writes /tmp/examlense-rows.sql
scp /tmp/examlense-rows.sql <you>@<vm>:~

# On the VM (docker needs sudo; -E preserves any PG_* overrides):
sudo -E ./import-rows.sh --restore-only ~/examlense-rows.sql
```

`--keep-existing` is baked into the dumped SQL, so the VM-side `--restore-only`
automatically inherits the non-destructive behavior. On the shared Hestia VM the
target container is already named `examlense-postgres` (the script's default), so
no `PG_*` env overrides are needed.

## 2. Files
```bash
export SUPABASE_URL='https://<REF>.supabase.co'
export SUPABASE_SERVICE_ROLE_KEY='<service-role key>'
node ./import-storage.mjs
```
Recursively downloads both buckets and writes them to
`backend/data/storage/<bucket>/<path>`, preserving the exact object paths. Since
the imported `source_file_url` / `storage_path` column values are those same
paths, the backend's `StorageService` resolves them with no rewrite.

## 3. Verify
- Restart the backend (`set -a; source backend/.env; set +a; ./gradlew bootRun`).
- `curl -H "Authorization: Bearer dev-local-token" http://localhost:8081/api/exams`
  should list the imported exams.
- Open one in the UI; confirm a figure/PDF renders (proves the file copy + signed
  URLs line up).

## Notes
- Idempotency: re-running **step 1** will fail on duplicate primary keys (good —
  it prevents silent double-imports). To redo, wipe first: `docker exec -i
  examlense-postgres psql -U examlense -d examlense -c "truncate exams cascade;"`.
- No users/passwords are migrated (single-user model); `owner_id` is a plain
  uuid column with no FK, so the flatten is a simple `UPDATE`.

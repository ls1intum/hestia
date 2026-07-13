# Updating ExamLense on the Hestia VMs

This is the day-to-day guide for shipping changes to an ExamLense deployment that is
**already running** on a Hestia VM. It does *not* cover first-time setup (Dockerfiles,
`compose.prod.yaml`, the initial VM directory, GitHub environments) — that's already done;
see the git history if you need to redo it from scratch.

Read `apps/learninggoalhub/DEPLOY.md` and `infra/traefik/README.md` for the shared-stack
conventions this app follows.

---

## Mental model

- ExamLense runs as **three containers** on the VM via `compose.prod.yaml`: `examlense-postgres`,
  `examlense-server` (Spring Boot), `examlense-web` (nginx serving the SPA and proxying `/api`).
  Only `web` joins the shared `hestia-edge` network and carries Traefik labels.
- The shared **Traefik** proxy routes `https://<APP_HOST>/examlense/*` to `web` and strips the
  `/examlense` prefix. TLS, ports 80/443, and the `hestia-edge` network are shared infra — you
  don't touch them when updating the app.
- **CI builds, but does not deploy (yet).** GitHub Actions builds and pushes images to GHCR on
  every push/PR. Automatic deploy-to-VM is **not active** — it needs the chair deployment
  gateway, which isn't wired up. So **deploying an update is a manual `pull` + `up -d` on the
  VM.** (When the gateway lands, this becomes a workflow dispatch; until then, it's the steps
  below.)
- Secrets live in **`/opt/hestia/examlense/.env` on the VM**, written by hand. Nothing needs to
  be set in GitHub for the manual flow. Optional but recommended there:
  `FILES_SIGNING_SECRET` (dedicated HMAC key for signed file URLs — without it the backend
  falls back to `API_AUTH_TOKEN` and logs a warning). `API_RATELIMIT_BEHIND_PROXY` defaults
  to `true` in `compose.prod.yaml` since the VMs sit behind Traefik.

### The two VMs

Reachable only via the **LRZ VPN**, over SSH with your own TUM identifier:

| Env | Host (`APP_HOST`) | Typical `IMAGE_TAG` |
| --- | --- | --- |
| test | `hestia-test.aet.cit.tum.de` | `pr-<N>` or `latest` |
| prod | `hestia.aet.cit.tum.de` | `latest` or a release tag |

Deploy directory on both: `/opt/hestia/examlense`. `docker` needs `sudo` on these VMs.

---

## The update cycle

### 1. Push your changes → CI builds the images

```bash
git add -A && git commit -m "…" && git push
```

Watch **Actions → examlense CI/CD**; wait for `build-backend` and `build-frontend` to go green.
They push:

```
ghcr.io/ls1intum/hestia/examlense-backend:<tag>
ghcr.io/ls1intum/hestia/examlense-frontend:<tag>
```

**Which `<tag>`:**
- Pushing commits to an **open PR** rebuilds the **same** `pr-<N>` tag — the tag string doesn't
  change, it just points at a newer image digest.
- Merging to **`main`** rebuilds `:latest`.
- Publishing a **release `vX.Y.Z`** builds `:vX.Y.Z`.

So you usually don't change `.env` — `IMAGE_TAG` already names the tag you keep re-pushing.

### 2. Pull + recreate on the VM

SSH into the target VM (on the VPN), then:

```bash
cd /opt/hestia/examlense
sudo docker login ghcr.io      # only if your cached GHCR credential expired
sudo docker compose -f compose.prod.yaml --env-file .env pull    # fetches the new digest
sudo docker compose -f compose.prod.yaml --env-file .env up -d   # recreates changed containers
```

`pull` compares digests, so it updates even when the tag string is unchanged. `up -d` recreates
only `server` + `web` (whose images changed); **`postgres` and its data volume are untouched.**

> Deploying a *different* tag instead (e.g. bumping test to a new PR, or prod to a release):
> edit `IMAGE_TAG` in `.env` first, then run the same two commands.

### 3. Flyway migrations apply themselves

When the new `server` container boots, **Flyway runs automatically**: it checks
`flyway_schema_history` and applies any pending `V<n>__*.sql` against the existing database. No
manual DB step. Confirm in the logs:

```bash
sudo docker compose -f compose.prod.yaml logs -f server
```

Look for `Migrating schema "public" to version <N>` → `Successfully applied … migration`, then
`Started …Application`. `Ctrl-C` stops following (not the container).

### 4. Verify

```bash
sudo docker compose -f compose.prod.yaml ps      # 3 up, postgres (healthy)
```

Then in a browser (on the VPN): `https://<APP_HOST>/examlense/`. Quick smoke test:
- SPA loads, assets resolve (no 404s), a sub-route reload (`/examlense/exams`) doesn't 404.
- `GET /examlense/api/healthz` returns OK.
- Live progress updates during a solve/evaluate (SSE).
- Upload a PDF → parsed figures/files render (validates signed storage URLs under the prefix).

---

## Migration safety (read before shipping schema changes)

- **Always add a NEW `V<n>__*.sql`.** Never edit a migration that has already been applied on the
  VM — Flyway validates checksums and the server will **refuse to start** on a mismatch.
- **A failed migration blocks startup** (`ddl-auto: validate` + Flyway). If `server` won't come
  up after an update, check `logs server` for a Flyway error first.
- **Migrations run against real, populated data** (including anything imported from Supabase). A
  new `NOT NULL` column needs a default or a backfill step in the same migration — don't assume
  an empty table.

---

## Rollback

Redeploy a known-good tag:

```bash
# in /opt/hestia/examlense/.env, set IMAGE_TAG to the previous good tag, then:
sudo docker compose -f compose.prod.yaml --env-file .env pull
sudo docker compose -f compose.prod.yaml --env-file .env up -d
```

Note this only rolls back **code**, not the database — a Flyway migration that already ran stays
applied. If a migration is the problem, you need a new forward migration that undoes it (or a DB
restore); don't downgrade the image and expect the schema to revert.

---

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `permission denied … /var/run/docker.sock` | Your user isn't in the `docker` group — use `sudo` for **every** docker command (login included; root and your user have separate credential stores). |
| `pull` says `denied` / `unauthorized` | GHCR login expired or wrong. `sudo docker login ghcr.io` with a **classic PAT** (scope `read:packages`), and authorize it for the `ls1intum` org if SSO prompts. |
| `pull` fetches nothing new | CI hadn't finished, or you're on the wrong `IMAGE_TAG`. Confirm the green build and that the tag exists on the GHCR package page. |
| `server` restarting after update | Almost always a Flyway migration error or a bad `.env` value — `logs server` says which. |
| `403` on SSE in `logs server` (stack through `asyncDispatch`) | Cosmetic teardown noise, not user-facing. Harmless. |
| `parse_metrics …_fkey` FK `WARN` | An exam was deleted/truncated while a parse was still in flight. Best-effort metrics only — harmless. Quiesce the server before truncating/importing to avoid it. |

---

## Manual data import (one-off)

Copying exams/files from the old Supabase project into a VM is a separate procedure — see
`scripts/import-from-supabase/README.md` (it documents the laptop→VM split with
`--dump-only` / `--restore-only` / `--keep-existing`).

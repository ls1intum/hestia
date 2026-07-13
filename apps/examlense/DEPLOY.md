# Deploying ExamLense on the Hestia VMs

ExamLense ships to the Hestia VMs through GitHub Actions: **CI builds the images, pushes
them to GHCR, and deploys them onto the VM over SSH** via the shared ls1intum reusable
workflows — the same setup the other chair apps use. A push to `main` auto-deploys to
Staging; a GitHub Release deploys to Production behind an approval gate. Deploying by hand
on the VM is now only a **fallback** for when CI is unavailable.

Read `apps/learninggoalhub/DEPLOY.md` and `infra/traefik/README.md` for the shared-stack
conventions this app follows.

---

## Mental model

- ExamLense runs as **three containers** on the VM via `compose.prod.yaml`: `examlense-postgres`,
  `examlense-server` (Spring Boot), `examlense-web` (nginx serving the SPA and proxying `/api`).
  None publish a host port. `web` joins the shared `hestia-edge` network and carries Traefik
  labels; `server` also joins `hestia-edge` only so it can reach LearningGoalHub's nginx
  container at `http://learninggoalhub-web`.
- The shared **Traefik** proxy routes `https://<APP_HOST>/examlense/*` to `web` and strips the
  `/examlense` prefix. TLS, ports 80/443, and the `hestia-edge` network are shared infra — you
  don't touch them when updating the app. The `/examlense` prefix is **hardcoded** in
  `compose.prod.yaml` (not read from the shared `APP_PATH_PREFIX` variable, which belongs to
  learninggoalhub), so ExamLense only needs the `APP_HOST` variable, not `APP_PATH_PREFIX`.
- **CI deploys automatically.** GitHub Actions (`.github/workflows/examlense-cicd.yml`) runs the
  backend tests, builds + pushes both images to GHCR, then deploys them to the VM over SSH
  through the chair's shared deployment gateway. You normally don't SSH in at all.
- Deploy secrets live in **GitHub environment secrets/variables**; the reusable deploy workflow
  writes them into `/opt/hestia/examlense/.env` on the VM (next to the injected `IMAGE_TAG`)
  before running `docker compose pull && up -d`. They are never committed.

### The two VMs

Reachable only via the **LRZ VPN**, over SSH with your own TUM identifier (only needed for the
manual fallback or to inspect logs):

| Env | Host (`APP_HOST`) | GitHub environment | Deployed by |
| --- | --- | --- | --- |
| test | `hestia-test.aet.cit.tum.de` | `Staging` | push to `main` (automatic) |
| prod | `hestia.aet.cit.tum.de` | `Production` | GitHub Release (approval gate) |

Deploy directory on both: `/opt/hestia/examlense`. `docker` needs `sudo` on these VMs unless
your user is in the `docker` group.

---

## CI/CD

Images are `ghcr.io/ls1intum/hestia/examlense/{server,client}:<tag>` (nested under
`examlense/`, matching the learninggoalhub convention). The `server` (backend) is a
**standalone Gradle project**, so its image is built from `apps/examlense/backend` (not the
repo root, and with no dependency on `libs/` or the root Gradle files); the `client` (frontend)
is built from its own directory.

| Trigger | Images built | Deploy |
| --- | --- | --- |
| Pull request | `…/examlense/{server,client}:pr-<N>` | — (build only, validation) |
| Push to `main` | `…:latest` | → **Staging** (automatic) |
| GitHub Release `vX.Y.Z` | `…:vX.Y.Z` | → **Production** (waits for approval) |
| Manual (`workflow_dispatch`) | per branch | → chosen environment/tag |

Job order (all on GitHub-hosted runners):

1. **`test-backend`** — runs the backend suite (unit + Testcontainers-Postgres integration).
   This is a hard gate: a red test never reaches `build`/`deploy`.
2. **`build-backend` + `build-frontend`** — build and push the two images to GHCR.
3. **`deploy`** (skipped on PRs) — SSHes to the VM through the chair's shared deployment
   gateway/bastion (`DEPLOYMENT_GATEWAY_*`, org-level config pulled in via `secrets: inherit`),
   writes `.env`, then runs `docker compose pull && up -d`. The reusable workflow applies the
   GitHub environment protection rules, so a `Production` deploy pauses until a required
   reviewer approves.

To deploy an already-built tag without pushing code, use **Actions → examlense CI/CD → Run
workflow** (`workflow_dispatch`) and pick the environment + image tag.

### GitHub setup (one-time, per repo — needs repo admin)

Create two **Environments** (Settings → Environments) named `Staging` and `Production`.
`Production` gets a *required reviewer* so its deploys pause for approval; `Staging` stays
unprotected.

Per environment, set:

App secrets are **namespaced with an `EXAMLENSE_` prefix** so they don't collide with the other
apps' secrets in the shared environment (learninggoalhub does the same with `LEARNINGGOALHUB_`).
`compose.prod.yaml` maps each app-scoped name to the generic env var the container reads (e.g.
`EXAMLENSE_SAIA_API_KEY` → `AI_API_KEY`).

- **Secrets (required):** `VM_HOST`, `VM_USERNAME`, `VM_SSH_PRIVATE_KEY` (SSH access to that
  VM — these stay generic), `EXAMLENSE_POSTGRES_PASSWORD`, `EXAMLENSE_SAIA_API_KEY` (the GWDG
  key, mapped to `AI_API_KEY`).
- **Secrets (optional):** `EXAMLENSE_API_AUTH_TOKEN` (must match the token baked into the
  frontend build, see below), `EXAMLENSE_FILES_SIGNING_SECRET` (dedicated HMAC key for signed
  file URLs — without it the backend falls back to `API_AUTH_TOKEN` and logs a startup warning),
  and any native-provider keys you want to enable: `EXAMLENSE_OPENAI_API_KEY` (gpt-* strategies),
  `EXAMLENSE_ANTHROPIC_API_KEY` (claude-*), `EXAMLENSE_GEMINI_API_KEY` (gemini-* PDF parsers).
  Omit a provider key to disable its strategies.
- **Variables (required):** `APP_HOST` — the VM's FQDN, must match the TLS cert SAN (shared,
  not prefixed — it's the same host for every app on the VM).
- **Variables (optional, all have compose defaults):** `POSTGRES_DB`, `POSTGRES_USER`,
  `JAVA_OPTS`, `AI_BASE_URL`, `LGH_BASE_URL`, `API_RATELIMIT_BEHIND_PROXY` (defaults to `true`
  in `compose.prod.yaml` since the VMs sit behind Traefik).
  Leave `LGH_BASE_URL` unset unless you intentionally need an override; the production default is
  the Docker-internal `http://learninggoalhub-web`, not the public `/learninggoalhub` URL.

The `DEPLOYMENT_GATEWAY_*` gateway secrets/variables are shared org-level config — they do
**not** need to be set per repo. Everything except the connection keys is written verbatim into
`.env` on the VM, so any value `compose.prod.yaml` references must exist as a secret or variable
here; the two with no default (`EXAMLENSE_POSTGRES_PASSWORD`, `EXAMLENSE_SAIA_API_KEY`) will fail the deploy if
missing.

> **Auth-token gotcha:** the deployed frontend bakes `VITE_API_AUTH_TOKEN` in at build time from
> the committed `frontend/.env.production`. The backend's `API_AUTH_TOKEN` (from the GitHub
> secret, or its `dev-local-token` default) **must equal that baked value**, or the SPA can't
> call the API. `VITE_*` values are not secret — they ship inside the bundle.

### VM setup (one-time, per VM)

The reusable workflow SSHes in (via the gateway) and runs compose, so the VM just needs a
deploy user with Docker access and the workflow's public key, plus the shared Traefik proxy
(see `infra/traefik/README.md` for the one-time `hestia-edge` network + cert setup):

```bash
sudo adduser github_deployment --disabled-password
sudo usermod -aG docker github_deployment
sudo mkdir -p /opt/hestia/examlense && sudo chown -R github_deployment /opt/hestia

# Authorize the deploy key: put its PUBLIC key in github_deployment's authorized_keys and
# store the PRIVATE key as the VM_SSH_PRIVATE_KEY environment secret; set VM_USERNAME=
# github_deployment and VM_HOST to this VM's address (as reachable from the gateway).
```

The `postgres` data and uploaded files live on named volumes (`postgres-data`,
`examlense-storage`), so they survive redeploys and image bumps.

---

## Flyway migrations apply themselves

When a new `server` container boots, **Flyway runs automatically**: it checks
`flyway_schema_history` and applies any pending `V<n>__*.sql` against the existing database. No
manual DB step. Confirm in the logs (SSH to the VM, on the VPN):

```bash
sudo docker compose -f compose.prod.yaml logs -f server
```

Look for `Migrating schema "public" to version <N>` → `Successfully applied … migration`, then
`Started …Application`. `Ctrl-C` stops following (not the container).

### Verify a deploy

In a browser (on the VPN): `https://<APP_HOST>/examlense/`. Quick smoke test:
- SPA loads, assets resolve (no 404s), a sub-route reload (`/examlense/exams`) doesn't 404.
- `GET /examlense/api/healthz` returns OK.
- Live progress updates during a solve/evaluate (SSE).
- Upload a PDF → parsed figures/files render (validates signed storage URLs under the prefix).

Or on the VM: `sudo docker compose -f compose.prod.yaml ps` — 3 up, postgres (healthy).

---

## Migration safety (read before shipping schema changes)

- **Always add a NEW `V<n>__*.sql`.** Never edit a migration that has already been applied on the
  VM — Flyway validates checksums and the server will **refuse to start** on a mismatch.
- **A failed migration blocks startup** (`ddl-auto: validate` + Flyway). If `server` won't come
  up after a deploy, check `logs server` for a Flyway error first.
- **Migrations run against real, populated data** (including anything imported from Supabase). A
  new `NOT NULL` column needs a default or a backfill step in the same migration — don't assume
  an empty table.

---

## Rollback

Redeploy a known-good tag via **workflow_dispatch** (Actions → examlense CI/CD → Run workflow):
pick the environment and set the image tag to the previous good tag. Or by hand on the VM:

```bash
# in /opt/hestia/examlense/.env, set IMAGE_TAG to the previous good tag, then:
sudo docker compose -f compose.prod.yaml --env-file .env pull
sudo docker compose -f compose.prod.yaml --env-file .env up -d
```

Note this only rolls back **code**, not the database — a Flyway migration that already ran stays
applied. If a migration is the problem, you need a new forward migration that undoes it (or a DB
restore); don't downgrade the image and expect the schema to revert.

---

## Manual deploy / fallback

If CI is unavailable, deploy by hand from a checkout of the repo on the VM (images must already
be in GHCR):

```bash
cd /opt/hestia/examlense                          # or a fresh checkout's apps/examlense
sudo docker login ghcr.io                         # only if your cached GHCR credential expired
# .env must have EXAMLENSE_POSTGRES_PASSWORD, EXAMLENSE_SAIA_API_KEY, APP_HOST, and IMAGE_TAG set
sudo docker compose -f compose.prod.yaml --env-file .env pull    # fetches the new digest
sudo docker compose -f compose.prod.yaml --env-file .env up -d   # recreates changed containers
```

`pull` compares digests, so it updates even when the tag string is unchanged. `up -d` recreates
only `server` + `web` (whose images changed); **`postgres` and its data volume are untouched.**

---

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Deploy job fails at `test-backend` | A backend test is red — fix it; nothing is built or deployed until it's green. |
| `Production` deploy stuck "waiting" | The environment's required reviewer hasn't approved yet (by design). |
| Deploy fails writing `.env` / missing var | A `compose.prod.yaml` value has no GitHub secret/variable. `EXAMLENSE_POSTGRES_PASSWORD` and `EXAMLENSE_SAIA_API_KEY` are mandatory; check `APP_HOST` too. |
| `permission denied … /var/run/docker.sock` (manual) | Your user isn't in the `docker` group — use `sudo` for **every** docker command (login included; root and your user have separate credential stores). |
| `pull` says `denied` / `unauthorized` (manual) | GHCR login expired or wrong. `sudo docker login ghcr.io` with a **classic PAT** (scope `read:packages`), and authorize it for the `ls1intum` org if SSO prompts. |
| `server` restarting after deploy | Almost always a Flyway migration error or a bad `.env` value — `logs server` says which. |
| SPA loads but every API call 401s | The frontend's baked `VITE_API_AUTH_TOKEN` and the backend's `API_AUTH_TOKEN` don't match. |
| `/examlense/api/lgh/courses` returns 502 | ExamLense reached its own backend, but the backend could not complete its LGH call. Check `LGH_BASE_URL` first: on the VM it should normally be unset or `http://learninggoalhub-web`. Then smoke-test from the server container with `curl http://learninggoalhub-web/api/courses` and inspect `logs server` for the underlying client error. |
| `403` on SSE in `logs server` (stack through `asyncDispatch`) | Cosmetic teardown noise, not user-facing. Harmless. |
| `parse_metrics …_fkey` FK `WARN` | An exam was deleted/truncated while a parse was still in flight. Best-effort metrics only — harmless. Quiesce the server before truncating/importing to avoid it. |

---

## Manual data import (one-off)

Copying exams/files from the old Supabase project into a VM is a separate procedure — see
`scripts/import-from-supabase/README.md` (it documents the laptop→VM split with
`--dump-only` / `--restore-only` / `--keep-existing`).

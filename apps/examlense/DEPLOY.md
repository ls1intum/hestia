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
  server tests, builds + pushes both images to GHCR, then deploys them to the VM over SSH
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
`examlense/`, matching the learninggoalhub convention). The `server` is a
**standalone Gradle project**, so its image is built from `apps/examlense/server` (not the
repo root, and with no dependency on `libs/` or the root Gradle files); the `client`
is built from its own directory.

| Trigger | Images built | Deploy |
| --- | --- | --- |
| Pull request | `…/examlense/{server,client}:pr-<N>` | — (build only, validation) |
| Push to `main` | `…:latest` | → **Staging** (automatic) |
| GitHub Release `vX.Y.Z` | `…:vX.Y.Z` | → **Production** (waits for approval) |
| Manual (`workflow_dispatch`) | per branch | → chosen environment/tag |

Job order (all on GitHub-hosted runners):

1. **`test-server`** — runs the server suite (unit + Testcontainers-Postgres integration).
   This is a hard gate: a red test never reaches `build`/`deploy`.
2. **`build-server` + `build-client`** — build and push the two images to GHCR.
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
`EXAMLENSE_OPENAI_API_KEY` → `OPENAI_API_KEY`).

- **Secrets (required):** `VM_HOST`, `VM_USERNAME`, `VM_SSH_PRIVATE_KEY` (SSH access to that
  VM — these stay generic), `EXAMLENSE_POSTGRES_PASSWORD`,
  `EXAMLENSE_FILES_SIGNING_SECRET` (dedicated HMAC key for signed
  file URLs — a fresh random value, unrelated to the auth token; see **Security model** below
  for why leaving it unset is not safe in production).
- **Secrets (recommended):** `EXAMLENSE_ADMIN_BOOTSTRAP_TOKEN` — a long random value that
  grants admin; leave unset once a real admin exists (see **Enrolling users**)
- **Secrets (optional):** `EXAMLENSE_API_AUTH_TOKEN` (must match the token baked into the
  client build, see below),
  and any native-provider keys you want to enable: `EXAMLENSE_OPENAI_API_KEY` (gpt-* strategies),
  `EXAMLENSE_ANTHROPIC_API_KEY` (claude-*), `EXAMLENSE_GEMINI_API_KEY` (gemini-* PDF parsers).
  Omit a provider key to disable its strategies.
- **Variables (required):** `APP_HOST` — the VM's FQDN, must match the TLS cert SAN (shared,
  not prefixed — it's the same host for every app on the VM).
- **Variables (optional, all have compose defaults):** `POSTGRES_DB`, `POSTGRES_USER`,
  `JAVA_OPTS`, `LGH_BASE_URL`, `API_RATELIMIT_BEHIND_PROXY` (defaults to `true`
  in `compose.prod.yaml` since the VMs sit behind Traefik).
  Leave `LGH_BASE_URL` unset unless you intentionally need an override; the production default is
  the Docker-internal `http://learninggoalhub-web`, not the public `/learninggoalhub` URL.

The `DEPLOYMENT_GATEWAY_*` gateway secrets/variables are shared org-level config — they do
**not** need to be set per repo. Everything except the connection keys is written verbatim into
`.env` on the VM, so any value `compose.prod.yaml` references must exist as a secret or variable
here; the one with no default (`EXAMLENSE_POSTGRES_PASSWORD`) will fail the deploy if
missing.

> **The client and server tokens no longer have to match.** Production builds ignore
> `VITE_API_AUTH_TOKEN` entirely (`src/lib/api/token-store.ts` gates it on `import.meta.env.DEV`),
> because honouring a value compiled into the bundle would mean every visitor arrived
> pre-authenticated as the shared user. It is not in the production bundle at all — but it *is*
> committed in this public repo, so treat the value as published.
>
> A practical consequence: you can set `EXAMLENSE_API_AUTH_TOKEN` to any random value without
> rebuilding the client, and paste that instead. Worth doing if you want the legacy user's
> pre-existing exams protected while you set up. Blank it entirely once you are done (see
> **First-run setup** below).

## Security model

Know what actually protects this deployment before you touch it. ExamLense is a thesis
prototype; the code says so itself — real per-user auth is deliberately deferred
(`server/src/main/java/app/config/SecurityConfig.java`). Nothing below is a bug report, but
none of it should come as a surprise later.

**The API is publicly routable.** Traefik strips the `/examlense` prefix and nginx proxies
`/api/` to the server, so `https://<APP_HOST>/examlense/api/...` resolves for anyone who can
reach the VM. The "Verify a deploy" step below uses exactly that path.

**Identity is per-user, but interim and open.** A first-time visitor is given their own
account automatically — no invite, nothing asked — and their own long-lived token;
`owner_id` is stamped from it, so each person sees only their own exams. This is a stand-in
until TUM SAML is live — see
[`docs/auth-and-saml-cutover.md`](docs/auth-and-saml-cutover.md).

**Anyone who can reach the host can get an account.** That is the intended trade behind the
VPN, but it is the whole security boundary: there is no approval step and no allowlist. Set
`OPEN_REGISTRATION=false` to freeze the instance to existing token holders if that ever stops
being acceptable.

**AI spend is bounded by two limits, and the global one is the real cap.**
`QUOTA_PARSE_PER_DAY` / `QUOTA_SOLVE_PER_DAY` (20 each) are per user, which is a fairness
control: one person cannot crowd out the rest. They bound the bill only in combination with
`REGISTRATIONS_PER_IP_PER_DAY` (default 5), because open registration otherwise lets anyone
trade a used-up account for a fresh allowance — the effective per-address ceiling is roughly
`(1 + 5) × 20`. `QUOTA_GLOBAL_PARSE_PER_DAY` / `QUOTA_GLOBAL_SOLVE_PER_DAY` (200 each) cap
usage across *all* accounts, counted from actual usage, so neither churning accounts nor
forging a proxy header moves them. **That is the limit to set if you care about the invoice.**
Only a SHA-256 of the registering address is stored, never the address.

**`X-Forwarded-For` parsing is topology-dependent.** Every proxy appends the address it
received the request from, so the header reaching the server reads
`[whatever the client sent] , real-client-ip , traefik-ip`. `API_RATELIMIT_TRUSTED_PROXY_HOPS`
(default 2 — Traefik, then this app's nginx) says how many trailing entries our own
infrastructure added; the client's address is the first of those. Set it too low and every
per-IP limit is evadable with one header; too high and it reads our own proxy's address,
which is the same for everyone and turns the per-IP caps into global ones. **Revisit it if
the edge topology changes**, and note that neither failure mode produces an error.

**IP is a poor proxy for "person" in both directions.** Several testers behind one campus NAT
address share the 3/day registration budget, so the fourth is refused; equally, one person on
several networks gets several budgets. Accepted for a temporary scheme; the global ceiling is
what makes it tolerable.

**A stolen token is a full account takeover.** Tokens live in `localStorage`, there is no
second factor, and nothing detects reuse from another machine. Revocation
(`DELETE /api/admin/users/{id}/tokens`, or the button in `/admin`) is the only remedy.
Accepted knowingly for a temporary scheme; it is the main reason not to let this off the VPN.

**TUM IDs are optional and self-declared.** Accounts start with a generated `anon-…` handle;
users may link their TUM ID from the account menu, and only linked accounts survive the SAML
cutover with their exams. Linking refuses an identifier another account already holds, but
nothing verifies that it is really yours — check the `/admin` roster before cutover.

**The shared bootstrap token bypasses all of that.** While `API_AUTH_TOKEN` is set, anyone
presenting it authenticates as the seeded legacy user and sees that user's exams. Treat the
value as **public**: it is committed to this repository (`client/.env.production`,
`.env.example`), which is a public monorepo. It is *not* in the production JavaScript bundle —
the client only honours it in dev builds — but the repo is the bigger exposure anyway. The
server logs a warning on every boot while it is set. **Blank it once users are enrolled**; that
is the step that actually ends shared access.

**Admin is a separate secret.** `ADMIN_BOOTSTRAP_TOKEN` (GitHub secret
`EXAMLENSE_ADMIN_BOOTSTRAP_TOKEN`) is what grants `ROLE_ADMIN` via a token, and it has **no
default** — unset means the only route to admin is the `users.is_admin` flag. It is deliberately
not the shared token: putting user administration behind a value published in a public
repository would let anyone on the VPN promote themselves and revoke other people's access. Use it
once to enrol and promote your real account from `/admin`, then unset it. Rotate it by changing
the secret and redeploying; the server refuses to treat it as admin if it equals
`API_AUTH_TOKEN`, and logs an error in that case.

**AI spend is metered per user.** `QUOTA_PARSE_PER_DAY` / `QUOTA_SOLVE_PER_DAY` (20 each by
default) cap LLM jobs per user over a rolling 24h window. The per-IP request limiter
(300 requests / 10 s) is still there and still useful: it runs *before* authentication, so it
is the only thing that can cheaply absorb unauthenticated floods, including of the registration endpoint.

**Endpoints reachable without a token:** `/api/healthz`, `OPTIONS /**`, `/error`,
`POST /api/auth/register` (how a visitor gets their first credential), and
`/api/files/**`. The last one is not open — it is gated by an HMAC signature and expiry
instead of the bearer token. That signature is keyed by `FILES_SIGNING_SECRET`, and **when
that is unset the server falls back to the auth token** — the same value that ships in the
bundle — which would let anyone mint valid file URLs for arbitrary storage paths. That is
why it is listed as required above.

**The LRZ VPN is the real boundary.** The shared Traefik proxy has no authentication, no IP
allowlist, and no forward-auth middleware. CORS is correctly scoped to `https://${APP_HOST}`,
but CORS is a browser control and does nothing against a non-browser client. Per the repo
[`SECURITY.md`](../../SECURITY.md), these prototypes are for VPN-internal or local academic
use — do not put this on a public IP without the auth layer first.

**Known caveat:** SSE passes the session token as a `?token=` query parameter, because
`EventSource` cannot set headers. Query strings land in the `examlense-web` nginx access log
and in browser history in a way an `Authorization` header does not — so with per-user tokens a
leaked log line is an account takeover, not just shared access.

### First-run setup

Users need nothing from you — they open the app and get an account. This is only about
getting *yourself* admin and taking over the pre-existing exams.

1. Set the GitHub secret `EXAMLENSE_ADMIN_BOOTSTRAP_TOKEN` to a long random value
   (`openssl rand -base64 32`) and deploy. This is your temporary admin credential — do not
   reuse `EXAMLENSE_API_AUTH_TOKEN`, which is public.
2. Open the app. You'll be given an ordinary account automatically; use **Account → Add your
   TUM ID** to name it, and note its id from `GET /api/me`.
3. Sign out, then paste the bootstrap value into the **Access key** box to become admin.
4. Take over the pre-existing exams, which all belong to the legacy user:

   ```sql
   update exams set owner_id = '<your-new-user-id>'
    where owner_id = '00000000-0000-0000-0000-000000000001';
   ```

   Your id is in `GET /api/me`, or in the `/admin` roster.
5. Still signed in as the bootstrap identity, open `/admin` and hit **Make admin** on your
   TUM ID. (You cannot change your own flag, which is why this is done from the bootstrap
   identity rather than your own account.)
6. Sign back in with your own access key. **Unset `EXAMLENSE_ADMIN_BOOTSTRAP_TOKEN` and
   redeploy** — admin now comes only from `users.is_admin`.
7. **Set `EXAMLENSE_API_AUTH_TOKEN` to an empty value and redeploy.** Until this is done,
   anyone who reads the token out of this repository can sign in as the legacy user.

The admin actions are also available over HTTP: `GET /api/admin/users`,
`PATCH /api/admin/users/{id}` (`{"is_admin":true}`), `DELETE /api/admin/users/{id}/tokens`.

Revoking someone: the **Revoke** button in `/admin`, or
`DELETE /api/admin/users/{id}/tokens`. Takes effect immediately — the principal cache is
cleared on revoke — and leaves their exams intact.

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
# .env must have EXAMLENSE_POSTGRES_PASSWORD, APP_HOST, and IMAGE_TAG set
sudo docker compose -f compose.prod.yaml --env-file .env pull    # fetches the new digest
sudo docker compose -f compose.prod.yaml --env-file .env up -d   # recreates changed containers
```

`pull` compares digests, so it updates even when the tag string is unchanged. `up -d` recreates
only `server` + `web` (whose images changed); **`postgres` and its data volume are untouched.**

---

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Deploy job fails at `test-server` | A server test is red — fix it; nothing is built or deployed until it's green. |
| `Production` deploy stuck "waiting" | The environment's required reviewer hasn't approved yet (by design). |
| Deploy fails writing `.env` / missing var | A `compose.prod.yaml` value has no GitHub secret/variable. `EXAMLENSE_POSTGRES_PASSWORD` is mandatory; check `APP_HOST` too. |
| `permission denied … /var/run/docker.sock` (manual) | Your user isn't in the `docker` group — use `sudo` for **every** docker command (login included; root and your user have separate credential stores). |
| `pull` says `denied` / `unauthorized` (manual) | GHCR login expired or wrong. `sudo docker login ghcr.io` with a **classic PAT** (scope `read:packages`), and authorize it for the `ls1intum` org if SSO prompts. |
| `server` restarting after deploy | Almost always a Flyway migration error or a bad `.env` value — `logs server` says which. |
| SPA loads but every API call 401s | The client's baked `VITE_API_AUTH_TOKEN` and the server's `API_AUTH_TOKEN` don't match. |
| `/examlense/api/lgh/courses` returns 502 | ExamLense reached its own server, but the server could not complete its LGH call. Check `LGH_BASE_URL` first: on the VM it should normally be unset or `http://learninggoalhub-web`. Then smoke-test from the server container with `curl http://learninggoalhub-web/api/courses` and inspect `logs server` for the underlying client error. |
| `403` on SSE in `logs server` (stack through `asyncDispatch`) | Cosmetic teardown noise, not user-facing. Harmless. |
| `parse_metrics …_fkey` FK `WARN` | An exam was deleted/truncated while a parse was still in flight. Best-effort metrics only — harmless. Quiesce the server before truncating/importing to avoid it. |

---

## Manual data import (one-off)

Copying exams/files from the old Supabase project into a VM is a separate procedure — see
`scripts/import-from-supabase/README.md` (it documents the laptop→VM split with
`--dump-only` / `--restore-only` / `--keep-existing`).

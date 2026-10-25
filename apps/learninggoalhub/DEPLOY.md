# Deployment

The app is deployed as four containers via `compose.prod.yaml`:

- **postgres** — pgvector database (private to this app)
- **server** — Spring Boot server (private, heap sized from `mem_limit`)
- **web** — nginx serving the client build and proxying `/api` to the server
- **backup** — daily `pg_dump` of the database into a host directory (see [Backups](#backups))

None of these publish a host port. TLS and host ports 80/443 are owned by the **shared
Traefik proxy** (`infra/traefik/`, see its README), which routes `https://<host>/learninggoalhub/*`
to the `web` container and strips the prefix. Each app on the VM stays fully isolated —
only the `web` container joins the shared `hestia-edge` network; database and server do not.

Local dev still uses `compose.yaml` (Postgres only) with `vite`/`bootRun`.

## Target VMs

Chair Hestia VMs, reachable only via the **LRZ VPN** over SSH with your own TUM identifier:

- `hestia-test.aet.cit.tum.de` — test
- `hestia.aet.cit.tum.de` — prod

## One-time VM setup (shared, do once per VM)

The TLS certificate and the shared proxy are set up once and serve every app on the VM:

```bash
# 1. Provision the RBG TLS certificate (pre-installed tool; covers the VM's hostname).
sudo rbg-cert --force-request   # wait ~5 min, then:
sudo rbg-cert                    # installs into /var/lib/rbg-cert/live/

# 2. Create the shared network the apps attach to.
sudo docker network create hestia-edge

# 3. Start the shared Traefik proxy (owns 80/443, terminates TLS).
sudo docker compose -f infra/traefik/compose.yaml up -d
```

If the VM hostname differs, adjust the cert filenames in `infra/traefik/dynamic_conf.yml`.

## CI/CD

Images are built by GitHub Actions and pushed to **GHCR**, then deployed onto the VM over SSH
by the shared ls1intum reusable workflows (`.github/workflows/learninggoalhub-cicd.yml`):

| Trigger | Images built | Deploy |
| --- | --- | --- |
| Pull request | `…/learninggoalhub/{server,client}:pr-<N>` | — (build only, validation) |
| Push to `main` | `…:latest` | → **Staging** (automatic) |
| GitHub Release `vX.Y.Z` | `…:vX.Y.Z` | → **Production** (waits for approval) |
| Manual (`workflow_dispatch`) | per branch | → chosen environment/tag |

Every run first runs the checks: the server and `shared-llm` test suites (Testcontainers, on the
runner's Docker) and the client's lint, typecheck and tests. The images are built only when all of
them pass, so a red test never deploys. After a deploy, a smoke-test job SSHes to the VM, waits for
the server container's healthcheck (`/actuator/health`, which includes the database) and requests
the client, `/api/courses` through nginx, and the public route through Traefik. A failing check
fails the workflow run; the new containers stay up, so roll back by deploying the previous tag
(`workflow_dispatch` with `image-tag`).

Build and deploy both run on GitHub-hosted runners via the shared ls1intum reusable
workflows, like the other chair apps (e.g. Hephaestus). The VMs sit behind the LRZ VPN, so the
deploy workflow reaches them over SSH **through the chair's shared deployment gateway/bastion**
(`DEPLOYMENT_GATEWAY_*`, provided as organization-level secrets/variables and pulled in via
`secrets: inherit`). The reusable workflow writes `.env` on the VM from the repository's
Actions secrets/variables (plus the injected `IMAGE_TAG`), then runs `docker compose pull && up -d`.

Images are `ghcr.io/ls1intum/hestia/learninggoalhub/{server,client}:<tag>`. The server image
is built from the repo root (multi-module Gradle build); the client from its own directory.

### GitHub setup (one-time, per repo — needs repo admin)

Create two **Environments** (Settings → Environments) named `Staging` and `Production`.
`Production` gets a *required reviewer* so its deploys pause for approval; `Staging` stays
unprotected. The environments only gate deploys and hold no values.

Secrets and variables are set once for the whole repository (Settings → Secrets and variables →
Actions → **Repository** secrets / variables). Every app in the monorepo deploys from the same
set, so app-specific values carry the app name as a prefix:

- **Secrets:** `VM_SSH_PRIVATE_KEY` (SSH access to the VM), `LEARNINGGOALHUB_POSTGRES_PASSWORD`,
  `LEARNINGGOALHUB_LOGOS_API_KEY` (chat and vision)
- **Variables:** `VM_HOST`, `VM_USERNAME`, `APP_HOST` (the VM's FQDN, must match the TLS cert
  SAN), and optionally `POSTGRES_DB`, `POSTGRES_USER`, `JAVA_OPTS`

The `DEPLOYMENT_GATEWAY_*` gateway secrets/variables are shared org-level config — they do not
need to be set per repo. Everything except the connection keys is written verbatim into `.env`
on the VM, so any value `compose.prod.yaml` references must exist as a secret or variable here.

### VM setup (one-time, per VM)

The reusable workflow SSHes in (via the gateway) and runs compose, so the VM just needs a
deploy user with Docker access and the workflow's public key:

```bash
sudo adduser github_deployment --disabled-password
sudo usermod -aG docker github_deployment
sudo mkdir -p /opt/hestia/learninggoalhub && sudo chown -R github_deployment /opt/hestia

# Authorize the deploy key: put its PUBLIC key in github_deployment's authorized_keys and
# store the PRIVATE key as the VM_SSH_PRIVATE_KEY repository secret; set VM_USERNAME=
# github_deployment and VM_HOST to this VM's address (as reachable from the gateway).
```

The server also needs the SAML service-provider key pair, which is not part of the image or the
repository. `compose.prod.yaml` mounts it read-only from `/opt/hestia/secrets/saml`:

```bash
sudo mkdir -p /opt/hestia/secrets/saml
# copy the key pair registered with TUM's IdP as exactly these two files:
#   /opt/hestia/secrets/saml/private-key.pem
#   /opt/hestia/secrets/saml/certificate.crt
sudo chmod 600 /opt/hestia/secrets/saml/private-key.pem
```

Without the files the server fails at startup; the healthcheck and the CI smoke test then report it.

### Manual deploy / fallback

If CI is unavailable, deploy by hand from a checkout of the repo on the VM (images must
already be in GHCR; `docker login ghcr.io` first if the package is private):

```bash
cd <repo>/apps/learninggoalhub
cp .env.example .env        # fill LEARNINGGOALHUB_{POSTGRES_PASSWORD,LOGOS_API_KEY}, APP_HOST; set IMAGE_TAG
sudo docker compose -f compose.prod.yaml --env-file .env pull
sudo docker compose -f compose.prod.yaml --env-file .env up -d
```

The app is then served at `https://<APP_HOST>/learninggoalhub/`.
`docker` needs `sudo` on these VMs unless your user is in the `docker` group.

### Notes

- The API keys and the Postgres password live only in the repository's Actions secrets (and the
  generated `.env` on the VM) — never committed.
- `APP_HOST` must match the TLS cert SAN and drives the Traefik labels in `compose.prod.yaml`. The
  path prefix `/learninggoalhub` is fixed: it is written into those labels and into the client's
  Vite `base`, so changing it means editing both.
- On the small (~4 GB) shared VM the server is capped to `mem_limit: 1500m` (heap = 70%).

## Backups

The `backup` container writes a `pg_dump` custom-format dump of the database once a day (and once
whenever the container is recreated) to `BACKUP_DIR` on the host, default
`/opt/hestia/backups/learninggoalhub`, named `learninggoalhub-<YYYYMMDD-HHMMSS>.dump`. Dumps older
than `BACKUP_KEEP_DAYS` (default 14) are deleted. A failed dump leaves no file and logs
`backup FAILED`:

```bash
sudo docker logs learninggoalhub-backup      # one line per dump
ls -lh /opt/hestia/backups/learninggoalhub
```

The dumps live on the same VM as the database. They cover a removed volume, a bad migration or a
destructive request, not the loss of the VM itself; copy them off the VM for that.

### Restore

Stop the server so nothing writes during the restore, restore the chosen dump into the running
postgres container, then start the server again (from the deployment directory,
`/opt/hestia/learninggoalhub` for CI deploys):

```bash
sudo docker compose -f compose.prod.yaml --env-file .env stop server
sudo docker compose -f compose.prod.yaml --env-file .env exec -T postgres \
  pg_restore -U learninggoalhub -d learninggoalhub --clean --if-exists --no-owner \
  < /opt/hestia/backups/learninggoalhub/learninggoalhub-<timestamp>.dump
sudo docker compose -f compose.prod.yaml --env-file .env start server
```

`--clean --if-exists` drops and recreates every object in the dump, so the tables and the Flyway
history return to the dump's state; objects created after the dump are left in place. To start
from an empty database instead, restore after `docker compose down -v` and `up -d postgres`.

# Setup guide: from a fresh VM to the running service

This guide takes an empty Ubuntu VM to a running Hestia service: one Spring Boot server, the three
web clients (nginx), one Postgres, the landing page, and Traefik in front. It is written for the
operator of a long-running service VM and also covers rebuilding a VM from scratch (the data then
starts empty unless you restore a backup, see the operations manual). Day-two work (monitoring,
updates, backups, key rotation) is in the [operations manual](operations-manual.md).

Parts that are specific to the TUM setup are marked **(TUM)**: the certificate tool `rbg-cert`, the
chair's deployment gateway and the `ls1intum` GitHub organisation. Elsewhere, replace them as the
step describes.

Placeholders used throughout: `<APP_HOST>` is the VM's fully qualified host name (it must match the
TLS certificate), `<deploy-user>` is the account CI logs in with, `<deploy-path>` is
`/opt/hestia/app`.

## 0. What runs where

```
Browser ──443──▶ Traefik (hestia-traefik, the only container with host ports)
                   ├─ /learninggoalhub ─▶ hestia-learninggoalhub-web (nginx) ─┐
                   ├─ /workshopper     ─▶ hestia-workshopper-web (nginx)     ─┼─▶ hestia-server :8080 ─▶ hestia-postgres
                   ├─ /examlense       ─▶ hestia-examlense-web (nginx)       ─┘
                   └─ / (everything else) ─▶ landing page (nginx)
                 hestia-backup: daily pg_dump to a host directory
```

- Only Traefik publishes ports (80, 443). The three client containers join the shared Docker network
  `hestia-edge` so Traefik can reach them; the server and Postgres stay on the private compose network.
- Traefik strips the path prefix and the nginx containers proxy `/api/` and the SAML paths to the
  server. One session login covers all three clients.
- The server image and the three client images are built by CI and pushed to GHCR; the VM only
  pulls them (`infra/compose.prod.yaml` references `${IMAGE_TAG}`, it does not build).
- Everything the deploy touches lives in `<deploy-path>` (`/opt/hestia/app`): the compose file, the
  `.env` the deploy writes, and `infra/postgres/init/01-roles.sql`.
- The landing page is a separate stack with its own workflow (`landing-page-cicd.yml`) and folder
  (`/opt/hestia/landing-page`). It serves the bare host `https://<APP_HOST>/`; its Traefik rule has
  the lowest priority, so the three prefixes above always win. It needs no secrets.

Files in this repository that this guide relies on:

| File | Purpose |
| --- | --- |
| `infra/compose.prod.yaml` | The application stack |
| `infra/traefik/compose.yaml`, `infra/traefik/dynamic_conf.yml` | The shared proxy and its certificate files |
| `infra/traefik/cert.d/50-restart-traefik` | Certificate renewal hook |
| `infra/postgres/init/01-roles.sql` | Creates the two database roles on first start |
| `infra/scripts/export-intake.sh` | Export of the material intake (see the operations manual) |
| `.github/workflows/hestia-cicd.yml` | Build, deploy and smoke test |
| `apps/landing-page/compose.prod.yaml`, `.github/workflows/landing-page-cicd.yml` | The landing page |

## 1. Requirements

- Ubuntu LTS VM, about 4 GB RAM (the server container is capped at 2500 MB), disk for the images,
  the Postgres volume, the file storage volume and the backups.
- A DNS name `<APP_HOST>` that resolves to the VM, and inbound TCP 80 and 443 from wherever the
  users are. Inbound SSH from the deploy path (see step 8). These are firewall rules of the network
  operator, not of the VM: ask for them together with the VM. **(TUM)** The VMs sit behind the LRZ
  VPN; the IT group opens 80/443 to the internet (or only to the TUM network for a test system).
- Outbound HTTPS to `ghcr.io` (Hestia's images), Docker Hub (`postgres`, `traefik`), the identity
  provider's metadata URL, the Logos gateway and, if enabled, the federation metadata and the model
  providers.
- A TLS certificate for `<APP_HOST>`. On the TUM AET VMs the tool `rbg-cert` provides it
  (step 4). Elsewhere, any certificate source works as long as the files end up where
  `infra/traefik/dynamic_conf.yml` points (adapt the file names there).

## 2. Install Docker

Follow the official instructions for Docker Engine and the Compose plugin on Ubuntu
(<https://docs.docker.com/engine/install/ubuntu/>), then check:

```bash
docker --version
docker compose version
sudo systemctl enable --now docker
```

Traefik must be v3.7 or newer; `infra/traefik/compose.yaml` pins `traefik:v3.7` (older versions
cannot talk to Docker Engine 29.x). Do not downgrade the tag.

## 3. Deploy user and folders

CI logs in over SSH as a dedicated user that may use Docker:

```bash
sudo adduser <deploy-user> --disabled-password
sudo usermod -aG docker <deploy-user>
sudo mkdir -p /opt/hestia/app /opt/hestia/landing-page /opt/hestia/secrets/saml /opt/hestia/backups/hestia
sudo chown -R <deploy-user> /opt/hestia/app /opt/hestia/landing-page /opt/hestia/backups
```

`/opt/hestia/secrets/saml` stays owned by root; the server only reads it (step 6).

The deploy user's authorized key is set up in step 8.

## 4. Edge: network, certificate, Traefik

The repository is not checked out on the VM, so copy the Traefik files there once (from a clone, on
your own machine):

```bash
scp -r infra/traefik <you>@<APP_HOST>:/tmp/traefik
ssh <you>@<APP_HOST> 'sudo mkdir -p /opt/hestia/traefik && sudo cp -r /tmp/traefik/. /opt/hestia/traefik/'
```

Then on the VM:

```bash
# The shared network the client containers join (once per VM).
sudo docker network create hestia-edge

# Certificate (TUM): request, wait about five minutes, install. Elsewhere: see step 1.
sudo rbg-cert --force-request
sudo rbg-cert
ls /var/lib/rbg-cert/live/

# Renewal hook: rbg-cert renews on its own, Traefik does not re-read the files behind the
# symlinks, so it is restarted after each renewal.
sudo mkdir -p /usr/local/cert.d
sudo install -m 755 /opt/hestia/traefik/cert.d/50-restart-traefik /usr/local/cert.d/
sudo rbg-cert --reinstall      # runs the hooks once for the current certificate

# Proxy.
cd /opt/hestia/traefik
sudo docker compose -f compose.yaml up -d
```

`infra/traefik/dynamic_conf.yml` names the certificate files by the VM's host name
(`/var/lib/rbg-cert/live/host:f:<vm-name>.fullchain.pem` and `.privkey.pem`). Edit the two pairs of
paths (certificates and default store) to the names you see in `ls /var/lib/rbg-cert/live/` before
starting Traefik. Keep the quotes: the file names contain colons.

Check:

```bash
sudo docker ps --filter name=hestia-traefik --format '{{.Status}}'
echo | openssl s_client -connect <APP_HOST>:443 -servername <APP_HOST> 2>/dev/null \
  | openssl x509 -noout -subject -enddate
```

The subject must match `<APP_HOST>` and the end date must be in the future. A certificate that
Windows or a browser reports as revoked means Traefik is serving an old certificate: see "Revoked
certificate" in the operations manual.

## 5. Database roles

Nothing to do by hand. The Postgres container runs `infra/postgres/init/01-roles.sql` once, on the
first start with an empty data directory. It creates `hestia_owner` (owns the database and the
module schemas, used by Flyway only) and `hestia_app` (the application's login, row access only),
with the passwords from `HESTIA_OWNER_PASSWORD` and `HESTIA_APP_PASSWORD`.

CI copies the file to `<deploy-path>/infra/postgres/init/01-roles.sql` before every deploy (job
`copy-db-init`); `compose.prod.yaml` mounts that folder. For a manual setup, copy it yourself:

```bash
mkdir -p /opt/hestia/app/infra/postgres/init
# from a clone: scp infra/postgres/init/01-roles.sql <deploy-user>@<APP_HOST>:/opt/hestia/app/infra/postgres/init/
```

Because the script only runs on an empty data directory, changing a password in `.env` later does
**not** change the role. See "Database passwords" in the operations manual.

The schemas and tables are created by Flyway when the server starts (as `hestia_owner`). There is no
manual schema step.

## 6. SAML: service-provider keys and identity provider

The server is a SAML service provider with the registration id `tum`. It signs requests and
decrypts assertions with one key pair that is not part of the image or the repository.
`compose.prod.yaml` mounts the folder `/opt/hestia/secrets/saml` read-only into the container and
expects exactly two files:

```
/opt/hestia/secrets/saml/private-key.pem
/opt/hestia/secrets/saml/certificate.crt
```

**Rebuilding a VM under the same host name:** reuse the old key pair if you still have it. Then the
identity provider's registration stays valid and logins work from the first start. With a new pair,
the identity provider must get the new certificate, and logins fail until it has it.

Otherwise create the pair on the VM (or on a trusted machine) and register the certificate with the
identity provider:

```bash
sudo openssl req -x509 -newkey rsa:3072 -nodes -days 1825 \
  -keyout /opt/hestia/secrets/saml/private-key.pem \
  -out    /opt/hestia/secrets/saml/certificate.crt \
  -subj "/CN=<APP_HOST>"
sudo chmod 600 /opt/hestia/secrets/saml/private-key.pem
```

The server image has no `USER` instruction, so the container reads the key as root and mode 600 is
enough. If a future image runs as a non-root user, the key must be readable by that user. A key the
server cannot read makes it fail at startup (`docker logs hestia-server`).

Register with the identity provider:

- **Entity ID and endpoints** are published by the server as SAML metadata at
  `https://<APP_HOST>/<client-prefix>/saml2/service-provider-metadata/tum`, for any of
  `/learninggoalhub`, `/workshopper` or `/examlense` (the clients' nginx forwards `/saml2/**` and
  `/login/saml2/**` with the prefix). Give the identity provider operator that metadata (or the
  certificate and the assertion consumer URL it shows). The metadata is only reachable once the
  server runs, so the first start happens before the registration is complete. Register **all
  three** prefixes: a prefix the provider does not know ends in an error at the provider, even when
  the other two work. The registration can take days (**(TUM)** the LRZ), so ask early.
- **Attributes** the identity provider must release: `schacHomeOrganization`,
  `eduPersonPrincipalName`, `displayName`, `mail`.
- **The identity provider** the server trusts comes from the environment only (no default in the
  server): `SAML_IDP_ENTITY_ID` and `SAML_IDP_METADATA_URI`. `compose.prod.yaml` defaults them to
  TUM's identity provider; set the two variables in the deploy environment (step 7) to use another.
  The server fetches the metadata at startup, so the VM must reach that URL.
- The same key pair serves signing and decryption. When it changes, the identity provider must
  learn the new certificate first (see "SAML keys" in the operations manual).

Without the two files the server does not start; the healthcheck and the CI smoke test then fail.

### Other universities (DFN-AAI federation)

Besides the identity provider above, the server can accept every identity provider of a SAML
federation such as DFN-AAI. It is off by default; then the login page `/<prefix>/saml2/discovery`
forwards straight to the identity provider above.

- **Registration.** The service provider must be registered in the federation's metadata
  administration with the same metadata as above (entity IDs
  `https://<APP_HOST>/<client-prefix>/saml2/service-provider-metadata/tum` for the three prefixes,
  the same certificate, the same assertion consumer URL `.../login/saml2/sso/tum`). Request the
  attributes `schacHomeOrganization` and `eduPersonPrincipalName` (plus `displayName`, `mail`).
  The registration id `tum` in these URLs stays, whatever identity provider a user picks.
- **Switch it on** with the variable `HESTIA_FEDERATION_ENABLED=true`. The server then downloads
  the metadata aggregate from `HESTIA_FEDERATION_METADATA_URL` (default: DFN-AAI production,
  `https://www.aai.dfn.de/metadata/dfn-aai-idp-metadata.xml`) at startup and every 6 hours, and
  accepts it only with a valid signature by the DFN-AAI metadata signing certificate that ships in
  the image (`server/src/main/resources/federation/dfn-aai.pem`; SHA-256 fingerprint
  `6E:FE:08:72:C3:C2:49:77:AF:67:31:F3:AC:30:F7:17:17:03:EF:FA:BB:6C:88:14:97:B3:E1:A5:DD:A5:3A:A1`,
  published by DFN). For the DFN-AAI test federation set
  `HESTIA_FEDERATION_METADATA_URL=https://www.aai.dfn.de/metadata/dfn-aai-test-metadata.xml`; the
  service provider must then be registered there. Another federation needs its own certificate:
  `HESTIA_FEDERATION_SIGNING_CERTIFICATE=file:/path/in/container.pem` (mount the file).
- The VM must reach the metadata URL. If the download fails or the signature does not verify, the
  server still starts, offers only the identity provider above, logs an error and retries after
  5 minutes; a failed refresh keeps the last good copy.
- **Clients** link to `/<prefix>/saml2/discovery`. That page shows the identity provider above as
  the first button and every other identity provider of the federation in a list searchable by
  name.
- **Organisations** (tenants) are created on the first login of a university, from
  `schacHomeOrganization` or, without it, the part of `eduPersonPrincipalName` after the `@`. A
  federation identity provider may only assert organisations listed as its `shibmd:Scope` in the
  federation metadata; any other login is rejected (`SAML login rejected: ...` in the server log).

Do not use the development login on a service VM. `ENVIRONMENT=Production` selects the `prod`
profile (SAML) and the server refuses to start the development login there
(`HESTIA_DEPLOY_TARGET`, check `DeployGuard`). The `Staging` target maps to the `local` profile,
which signs every request in as one fixed development user. That is for demos on a closed test
system only.

## 7. Secrets and variables (`.env`)

The deploy writes `<deploy-path>/.env` on every run from the GitHub environment's secrets and
variables, plus `ENVIRONMENT` and `IMAGE_TAG`. So the GitHub environment is the source of truth and
a hand-edited `.env` is overwritten by the next deploy. Every `${...}` in `compose.prod.yaml` must
exist there; the preflight job in CI checks that the compose file is valid with placeholder values.

Names only (never commit or paste values; generate each random value with `openssl rand -hex 32`):

**Secrets**

| Name | Used for | Required |
| --- | --- | --- |
| `HESTIA_POSTGRES_PASSWORD` | Postgres superuser `postgres` (healthcheck, backup, export script) | yes |
| `HESTIA_OWNER_PASSWORD` | role `hestia_owner` (Flyway) | yes |
| `HESTIA_APP_PASSWORD` | role `hestia_app` (the application) | yes |
| `HESTIA_LOGOS_API_KEY` | Logos gateway (one key for the whole server) | yes |
| `EXAMLENSE_FILES_SIGNING_SECRET` | HMAC key of signed file URLs; without it every restart invalidates all file URLs | yes |
| `EXAMLENSE_OPENAI_API_KEY`, `EXAMLENSE_ANTHROPIC_API_KEY`, `EXAMLENSE_GEMINI_API_KEY` | ExamLense's direct provider calls; a missing key turns the matching strategies off | no |
| `VM_SSH_PRIVATE_KEY` | CI's SSH access to the VM (step 8) | yes |

**Variables**

| Name | Meaning | Required |
| --- | --- | --- |
| `APP_HOST` | `<APP_HOST>`; drives the Traefik routing rules and CORS | yes |
| `VM_HOST`, `VM_USERNAME` | where CI connects and as whom | yes |
| `SAML_IDP_ENTITY_ID`, `SAML_IDP_METADATA_URI` | the identity provider (default: TUM's) | no |
| `HESTIA_FEDERATION_ENABLED`, `HESTIA_FEDERATION_METADATA_URL` | logins through the DFN-AAI federation (default: off; production aggregate), see step 6 | no |
| `HESTIA_PLATFORM_ADMINS` | comma-separated eduPersonPrincipalNames with ExamLense's admin role | no |
| `HESTIA_AI_LIMIT_EXAM_PARSE`, `HESTIA_AI_LIMIT_EXAM_SOLVE` | per-user limits per 24 hours: exams read in (default 10) and solve runs (default 30); 0 = off | no |
| `JAVA_OPTS`, `BACKUP_DIR`, `BACKUP_KEEP_DAYS`, `API_RATELIMIT_BEHIND_PROXY`, `API_RATELIMIT_TRUSTED_PROXY_HOPS`, `OPENAI_BASE_URL`, `ANTHROPIC_BASE_URL` | tuning, defaults in the compose file | no |

Set automatically by the deploy: `ENVIRONMENT` (`Staging` or `Production`, becomes the Spring profile
and `HESTIA_DEPLOY_TARGET`) and `IMAGE_TAG` (`sha-<commit>` of the deployed build, or the release tag
such as `v1.0.0` on Production).

Use new random values for each environment; do not copy Staging's passwords to Production.

Leave `API_RATELIMIT_TRUSTED_PROXY_HOPS` at 2 (Traefik, then nginx). Do not let Traefik trust
forwarded headers from clients and do not publish any container port other than Traefik's: the
per-IP limits depend on the leftmost `X-Forwarded-For` entry set by Traefik.

## 8. Deploy access via the CI

CI reaches the VM over SSH. **(TUM)** That goes through the chair's deployment gateway
(organisation-level `DEPLOYMENT_GATEWAY_*` secrets and variables, passed with `secrets: inherit`);
a service VM with a directly reachable SSH port needs the gateway settings removed from the
workflow's `deploy`, `copy-db-init` and `smoke-test` jobs.

**Outside the `ls1intum` organisation** (a fork or another organisation), three more things change:
the image names in `infra/compose.prod.yaml` and `apps/landing-page/compose.prod.yaml`
(`ghcr.io/ls1intum/hestia/...`) must name your registry; the reusable workflows
`ls1intum/.github/...` must be reachable from your repository or be replaced by your own build and
deploy steps; and the VM must be allowed to pull your images (see "GHCR access" in the operations
manual).

1. Create a key pair for CI on any trusted machine:
   `ssh-keygen -t ed25519 -f hestia-deploy -C hestia-ci -N ""`.
2. Append `hestia-deploy.pub` to `~<deploy-user>/.ssh/authorized_keys` on the VM
   (mode 600, folder 700, owned by the deploy user).
3. Store the private key as the GitHub secret `VM_SSH_PRIVATE_KEY`, then delete the local copy.
4. Set `VM_HOST` and `VM_USERNAME` (the deploy user) and `APP_HOST`.
5. GitHub **Environments** hold the values: `Staging` (test VM) and `Production` (service VM). The
   workflow's `environment:` value selects the environment, and the deploy writes it into `.env` as
   `ENVIRONMENT`.
6. Give `Production` a **required reviewer** (Settings → Environments → Production → Required
   reviewers). Without one, every published release deploys at once. Check it with
   `gh api repos/<owner>/<repo>/environments --jq '.environments[] | {name, rules: [.protection_rules[].type]}'`:
   `Production` must list `required_reviewers`.

Which run deploys where (`hestia-cicd.yml`; `landing-page-cicd.yml` follows the same scheme):

| Trigger | Image tag | Environment |
| --- | --- | --- |
| push to `main` | `sha-<commit>` | `Staging` |
| GitHub release published | the release tag, e.g. `v1.0.0` | `Production` (after approval) |
| manual run (`workflow_dispatch`) | the given tag, or `sha-<commit>` | the chosen one |

A release therefore deploys the application and the landing page together.

The deploy folder is fixed to `/opt/hestia/app` in the workflow (`DEPLOY_PATH` and
`deployment-base-path`; the preflight script `scripts/deploy-preflight.sh` reads the same values). A
different folder on the VM makes the deploy copy files to a place the compose bind mount
(`./infra/postgres/init`) does not see.

## 9. First deployment and check

1. Make sure steps 3 to 8 are done.
2. Trigger a deploy. Staging: push to `main`, or
   `gh workflow run "hestia CI/CD" --ref main`. Production: publish a GitHub release of `main`
   (`gh release create v1.0.0 --target main --generate-notes`) and approve the waiting deploy in the
   run; or `gh workflow run "hestia CI/CD" --ref main -f environment=Production`. The run tests,
   builds the four images, copies the role script, writes `.env`, pulls and starts the stack, and
   runs the smoke test. A release also deploys the landing page.
3. Watch the run (`gh run watch`). The smoke test waits up to five minutes for the server's health
   check, requests each client through nginx and through Traefik, and checks the Java4Shell
   protection (CVE-2026-114938): Java 21.0.13.1 or later and `-XX:-UseBASE64Intrinsics`. The server
   itself also refuses to start on a deploy target with an open JVM (`Java4ShellGuard` in its log).
4. Check by hand on the VM:

```bash
cd /opt/hestia/app
sudo docker compose -f compose.prod.yaml --env-file .env ps
sudo docker logs --tail 50 hestia-server
curl -fsS https://<APP_HOST>/examlense/api/healthz
sudo docker exec hestia-server sh -c 'echo $JAVA_VERSION'   # jdk-21.0.13.1+2 or later
```

All containers must be `Up`, `hestia-postgres` and `hestia-server` `healthy`. On Production, a
browser on each of `https://<APP_HOST>/learninggoalhub/`, `/workshopper/` and `/examlense/` must
send you to the identity provider, and you must come back logged in (this needs step 6's
registration). `https://<APP_HOST>/` shows the landing page.

Manual fallback when CI is unavailable (images must exist in GHCR; `sudo docker login ghcr.io` with a
token that has `read:packages` if the packages are private):

```bash
cd /opt/hestia/app
# .env must hold every variable of step 7 plus ENVIRONMENT and IMAGE_TAG
sudo docker compose -f compose.prod.yaml --env-file .env pull
sudo docker compose -f compose.prod.yaml --env-file .env up -d
```

## 10. Checklist for the takeover

- [ ] Firewall: 80/443 open for the users, SSH reachable from the deploy path (step 1)
- [ ] `docker --version` and `docker compose version` work, `<deploy-user>` is in the `docker` group
- [ ] `/opt/hestia/app`, `/opt/hestia/landing-page` and `/opt/hestia/backups` owned by `<deploy-user>`
- [ ] `hestia-edge` exists (`docker network ls`)
- [ ] Certificate valid for `<APP_HOST>`, hook installed (`ls /usr/local/cert.d/`)
- [ ] `hestia-traefik` running and serving the right certificate
- [ ] SAML key pair in `/opt/hestia/secrets/saml`, certificate registered with the identity provider
  for all three prefixes, attributes released
- [ ] All secrets and variables of step 7 set in the GitHub environment, with values of their own
- [ ] `Production` has a required reviewer (step 8)
- [ ] Deploy key authorised, first CI deploy green, smoke test green (including Java 21.0.13.1+)
- [ ] Landing page deployed, login works on all three prefixes
- [ ] First backup exists in `/opt/hestia/backups/hestia` and a restore drill was done once
  (operations manual)
- [ ] External monitoring check configured (operations manual)

# Operations manual

Day-two operation of the Hestia service on its VM: monitoring, updates and rollback, backup and
restore, key rotation, typical failures and the intake export. The VM is set up as described in the
[setup guide](setup-guide.md). Placeholders: `<APP_HOST>` is the VM's host name, `<deploy-path>` is
`/opt/hestia/app`. All `docker compose` commands run in `<deploy-path>`:

```bash
cd /opt/hestia/app
alias hc='sudo docker compose -f compose.prod.yaml --env-file .env'
```

## 1. Containers at a glance

| Container | Role | Health |
| --- | --- | --- |
| `hestia-traefik` | TLS, routing (started from `infra/traefik/compose.yaml`, separate compose project) | none; check the certificate |
| `hestia-server` | the one Spring Boot server | Docker healthcheck on `/actuator/health` (includes the database) |
| `hestia-postgres` | database `hestia` | `pg_isready` |
| `hestia-learninggoalhub-web`, `hestia-workshopper-web`, `hestia-examlense-web` | nginx per client | `hestia-learninggoalhub-web` has a healthcheck, the others depend on the server being healthy |
| `hestia-backup` | daily `pg_dump` | log line per dump |

Docker restarts all of them after a crash or a VM reboot (`restart: unless-stopped`). Traefik is not
part of the application compose file, so after a rebuilt VM start it with the setup guide's step 4.

## 2. Monitoring

### Health

On the VM:

```bash
hc ps
sudo docker inspect -f '{{.State.Health.Status}}' hestia-server hestia-postgres
sudo docker exec hestia-server curl -fsS http://localhost:8080/actuator/health
```

`/actuator/health` is the only exposed actuator endpoint and is **not** routed to the outside (the
clients' nginx proxies only `/api/` and the SAML paths). Do not open it in Traefik.

### External check

The server answers without login on `GET /examlense/api/healthz` (through Traefik and nginx), which
also proves that TLS, routing and the server work. Point an external monitor (uptime service or a
cron job on another machine) at it, every minute or two, and alert on anything but HTTP 200:

```bash
curl -fsS -o /dev/null -w '%{http_code}\n' https://<APP_HOST>/examlense/api/healthz
curl -fsS -o /dev/null -w '%{http_code}\n' https://<APP_HOST>/workshopper/api/workshop/health
```

Also alert on the certificate's remaining validity (alert below 14 days):

```bash
echo | openssl s_client -connect <APP_HOST>:443 -servername <APP_HOST> 2>/dev/null | openssl x509 -noout -enddate
```

A login test needs a real identity-provider account and is manual: open
`https://<APP_HOST>/learninggoalhub/` and check that you are sent to the identity provider.

### Logs

```bash
sudo docker logs --tail 200 -f hestia-server        # application, Flyway, SAML
sudo docker logs --tail 100 hestia-postgres
sudo docker logs --tail 100 hestia-traefik          # routing, certificate problems
sudo docker logs --tail 20  hestia-backup           # one "backup written" line per day
```

Container logs are kept by Docker's default driver; configure log rotation in
`/etc/docker/daemon.json` (`"log-driver": "json-file"`, `"log-opts": {"max-size": "20m", "max-file": "5"}`)
if the VM has no central logging. Docker needs a restart for the change and it only applies to
containers created afterwards (redeploy once).

### What to watch

- Disk: `df -h /` and `sudo docker system df`. Postgres volume, file storage, images and backups
  share the disk. Remove unused images after updates: `sudo docker image prune -f`.
- Memory: the server is capped at 2500 MB (`mem_limit`), the heap at 70 % of that. Repeated
  `OOMKilled` (`sudo docker inspect -f '{{.State.OOMKilled}}' hestia-server`) means the cap or the
  VM is too small.
- Backups: the newest file in `/opt/hestia/backups/hestia` must be less than 26 hours old
  (see section 4).
- Certificate and Traefik: see above.
- The security note of the server image: it starts the JVM with `-XX:-UseBASE64Intrinsics`
  (workaround for CVE-2026-114938 until the base image is Java 21.0.13.1 or later). The smoke test
  checks the flag after every deploy. Remove the flag only after `java -version` in the running
  image shows a fixed release.

## 3. Updates and rollback

### How a deploy works

Every push to `main` runs the whole pipeline: tests, build of four images tagged
`sha-<full commit hash>`, copy of the role script, deploy, smoke test. A failed test stops before
any image is built. The deploy writes `.env` (with `IMAGE_TAG=sha-<commit>`), runs
`docker compose pull` and `up -d`; only containers whose image changed are recreated, Postgres and
its volume stay. Flyway applies new migrations when the server starts.

### Update

Normal path: merge a pull request into `main`; CI deploys. To redeploy without a code change, run the
workflow by hand:

```bash
gh workflow run "hestia CI/CD" --ref main
gh run watch
```

Check afterwards: the run is green including the smoke test, and `sudo docker inspect -f
'{{.Config.Image}}' hestia-server` shows the expected `sha-` tag.

Base images (`postgres:16`, `traefik:v3.7`, `eclipse-temurin:21-jre`) move with their tags. Pull and
recreate them on purpose: `hc pull && hc up -d` for the application, and for Traefik
`cd /opt/hestia/traefik && sudo docker compose pull && sudo docker compose up -d`. A new Postgres
minor version is a drop-in replacement, a new major version is not (do a dump and restore).

### Rollback

Redeploy a known-good image tag. Find it in the list of successful runs (`gh run list --workflow
"hestia CI/CD"`) or in GHCR. Then:

```bash
gh workflow run "hestia CI/CD" --ref main -f image-tag=sha-<good-commit-hash>
gh run watch
```

The `image-tag` input must be an existing tag of the server image in GHCR (the pipeline still runs
the tests and builds a new image for the current commit; the deploy uses the tag you passed). The
tag is written into `.env` as `IMAGE_TAG` and applies to the server and all three clients.

By hand on the VM, when CI is unavailable: set `IMAGE_TAG=sha-<good-commit-hash>` in `.env` and run
`hc pull && hc up -d`. The next CI deploy overwrites `.env` again.

**A rollback restores code, not the database.** A Flyway migration that already ran stays applied.
The older server may refuse to start on a newer schema, or run on it incorrectly. If a migration is
the problem, either ship a new forward migration that undoes it (never edit an applied migration:
Flyway validates checksums and the server refuses to start on a mismatch) or restore a backup taken
before the update (section 4). Before an update that contains a migration, take a fresh dump:

```bash
sudo docker exec hestia-postgres sh -c 'pg_dump -U postgres -Fc hestia' \
  > /opt/hestia/backups/hestia/before-update-$(date +%Y%m%d-%H%M%S).dump
```

(The backup container's pruning only deletes files named `hestia-*.dump`, so this dump stays until
you remove it.)

## 4. Backup and restore

### What is backed up

`hestia-backup` writes a custom-format `pg_dump` of the whole database `hestia` once a day (and
whenever the container is recreated) into `/opt/hestia/backups/hestia` (`BACKUP_DIR`), as
`hestia-<YYYYMMDD-HHMMSS>.dump`. After each successful dump it deletes dumps older than
`BACKUP_KEEP_DAYS` (default 14); a failed dump leaves no file, logs `backup FAILED` and never deletes
older dumps.

The database holds all module data **including the material intake files** (stored as bytes in
`materials.intake_file`).

**Not covered:** the named volume `storage` (mounted at `/app/data` in the server), which holds the
files that ExamLense stores (uploaded exams and their figures). Back it up separately, for example
daily from cron:

```bash
sudo docker run --rm -v app_storage:/data:ro -v /opt/hestia/backups:/out alpine \
  tar czf /out/storage-$(date +%Y%m%d).tar.gz -C /data .
```

The volume is named `<compose project>_storage`; the compose project is the folder name, so
`app_storage` for `/opt/hestia/app`. Verify with `sudo docker volume ls` first.

**Off the VM:** all dumps live on the same VM as the database. They protect against a bad
migration, a deleted volume or a destructive request, not against losing the VM. Copy
`/opt/hestia/backups` to another system on a schedule (the service VM operator's backup system, or
`rsync` to another host) and keep that copy under access control: dumps contain personal data.

Check that backups are running:

```bash
ls -lh /opt/hestia/backups/hestia | tail -5
sudo docker logs --tail 5 hestia-backup
```

### Restore into the running database

Use this when the data is damaged and you accept losing everything since the dump. It runs as the
`postgres` superuser and keeps the original owners and grants (`hestia_owner`, `hestia_app`), which
Flyway and the application need, so do **not** add `--no-owner`.

```bash
hc stop hestia-server
sudo docker exec -i hestia-postgres pg_restore -U postgres -d hestia --clean --if-exists \
  < /opt/hestia/backups/hestia/hestia-<timestamp>.dump
hc start hestia-server
sudo docker logs --tail 50 hestia-server
```

`--clean --if-exists` drops and recreates each object in the dump, so tables and the Flyway history
return to the dump's state; objects created after the dump stay behind. A dump from before a
migration makes the server run that migration again at the next start, which is the intended
behaviour. Warnings about roles or missing objects on a clean database are expected, errors that stop
the restore are not.

### Restore into an empty database (new VM, lost volume)

1. Set up the VM until step 4 of the setup guide and deploy once, so that `hestia-postgres` is
   running with its roles (the deploy creates them on the empty data directory). Stop the server:
   `hc stop hestia-server`.
2. Copy the dump to the VM and restore it as above (`--clean --if-exists` also works on a fresh
   database).
3. Start the server and check the health endpoint.

To start from a completely empty database again: `hc down`, remove the volume
(`sudo docker volume rm app_postgres-data`, check the name with `docker volume ls`), then `hc up -d`.
The role script runs again on the empty data directory. This **deletes all data**; take a dump first.

### Restore drill

A dump that was never restored is not yet a backup. Do the drill once after setup, after every
migration that rewrites data, and at least once a quarter. It restores the newest dump into a
disposable Postgres container and leaves the live database untouched.

```bash
dump=$(ls -t /opt/hestia/backups/hestia/hestia-*.dump | head -1)
echo "drill with $dump"

sudo docker run -d --name hestia-drill -e POSTGRES_PASSWORD=drill -e POSTGRES_DB=hestia postgres:16
until sudo docker exec hestia-drill pg_isready -U postgres -d hestia >/dev/null 2>&1; do sleep 1; done

# Roles the dump refers to (owners and grants), without the real passwords.
sudo docker exec hestia-drill psql -U postgres -c "create role hestia_owner login; create role hestia_app login;"

sudo docker exec -i hestia-drill pg_restore -U postgres -d hestia --exit-on-error < "$dump"

# Evidence: Flyway state and row counts per schema.
sudo docker exec hestia-drill psql -U postgres -d hestia -c \
  "select max(version::int) as latest_migration, count(*) as applied from flyway_schema_history where success;"
sudo docker exec hestia-drill psql -U postgres -d hestia -c \
  "select schemaname, relname, n_live_tup from pg_stat_user_tables order by 1, 2;"

sudo docker rm -f hestia-drill
```

The drill passes when `pg_restore` ends without error, `latest_migration` equals the one in the live
database (`sudo docker exec hestia-postgres psql -U postgres -d hestia -c "select max(version::int)
from flyway_schema_history where success;"`), and the row counts are plausible (`n_live_tup` is
approximate right after a restore; run `analyze` first for exact estimates, or `select count(*)` on a
table you care about). Write the date and result into the operator's log.

## 5. Key rotation

Rotate on a schedule (the SAML certificate before it expires, keys at least yearly) and immediately
when a key may have leaked. General rule: the GitHub environment is the source of truth for every
value in `.env`. Change the secret there, then redeploy so the deploy rewrites `.env` and recreates
the containers (`gh workflow run "hestia CI/CD" --ref main`). A change made only in `.env` on the VM
is lost at the next deploy.

### SAML keys

The server signs requests and decrypts assertions with
`/opt/hestia/secrets/saml/private-key.pem` and `certificate.crt` (one pair, no second key during a
changeover), so logins break between the swap and the identity provider accepting the new
certificate. Plan a short window.

1. Generate a new pair next to the old one (`new-private-key.pem`, `new-certificate.crt`, command in
   the setup guide, step 6).
2. Send the new certificate to the identity provider operator and agree on the switch time.
3. At that time: move the old pair to a backup name outside `/opt/hestia/secrets/saml`, put the new
   files in under the two fixed names, mode 600 for the key, and restart:
   `hc restart hestia-server`.
4. Test a login with a real account. If it fails, put the old pair back and restart.
5. Securely delete the old key after the identity provider dropped the old certificate.

Changing the identity provider itself (`SAML_IDP_ENTITY_ID`, `SAML_IDP_METADATA_URI`) is a variable
change plus redeploy; the server reads the provider's metadata at startup.

### Logos key

1. Create a new key in the Logos gateway (or ask its operator), keep the old one valid.
2. Set the GitHub secret `HESTIA_LOGOS_API_KEY` to the new value and redeploy.
3. Check the server log for model calls without authorization errors, then revoke the old key.

The ExamLense provider keys (`EXAMLENSE_OPENAI_API_KEY`, `EXAMLENSE_ANTHROPIC_API_KEY`,
`EXAMLENSE_GEMINI_API_KEY`) rotate the same way.

### Database passwords

The role script only runs on an empty data directory, so changing a password in `.env` does not
change the role in Postgres. For each of the three roles (`postgres` = `HESTIA_POSTGRES_PASSWORD`,
`hestia_owner` = `HESTIA_OWNER_PASSWORD`, `hestia_app` = `HESTIA_APP_PASSWORD`), change the role and
the secret together, one role at a time, with a fresh backup first:

```bash
# 1. New value (kept in a shell variable, not in the shell history).
read -rs NEW
# 2. Change the role in the running database; psql substitutes :'pw' when the statement comes on stdin.
printf '%s\n' "alter role hestia_app password :'pw';" \
  | sudo docker exec -i hestia-postgres psql -U postgres -v pw="$NEW"
```

3. Immediately set the same value as the GitHub secret and redeploy. The server (for
   `hestia_app`, `hestia_owner`) and the backup container (for `postgres`) are recreated with the
   new value. Between steps 2 and 3 new database connections with the old value fail, so keep the
   gap short; the running server keeps its open connections until the redeploy.
4. Check `sudo docker inspect -f '{{.State.Health.Status}}' hestia-server` and that
   `hestia-backup` writes a dump (`sudo docker logs --tail 5 hestia-backup`).

### Deploy key (`VM_SSH_PRIVATE_KEY`)

1. `ssh-keygen -t ed25519 -f hestia-deploy-new -C hestia-ci -N ""` on a trusted machine.
2. Append `hestia-deploy-new.pub` to `~<deploy-user>/.ssh/authorized_keys` on the VM (keep the old
   line for now).
3. Replace the GitHub secret `VM_SSH_PRIVATE_KEY` with the new private key and delete the local
   copy.
4. Run a deploy (`gh workflow run "hestia CI/CD" --ref main`). It must be green, including the
   smoke test, which also logs in over SSH.
5. Remove the old public key line from `authorized_keys`.

The gateway keys (`DEPLOYMENT_GATEWAY_*`) belong to the chair's organisation-level configuration;
their owner rotates them. If the VM is reached directly, there are no gateway keys.

### `FILES_SIGNING_SECRET` (`EXAMLENSE_FILES_SIGNING_SECRET`)

1. `openssl rand -hex 32`, set it as the GitHub secret, redeploy.
2. Effect: file URLs issued before the change stop working (HTTP 403). Open pages show broken
   images until they reload; the URLs are created again on every page load, so no data is lost.
   Rotate outside working hours.

### GHCR access

If the packages are private, the VM needs a registry login: `sudo docker login ghcr.io` with a token
that has `read:packages`. Rotate the token by logging in again with the new one; `docker pull` fails
with `denied` or `unauthorized` when it expired.

## 6. Typical failures

| Symptom | Cause and fix |
| --- | --- |
| Test or image-build job red with `429 Too Many Requests` or "Could not resolve all artifacts" (Gradle) | Maven Central rate-limits the shared runners. `gradle.properties` already retries downloads six times with growing pauses; re-run the failed jobs (`gh run rerun <id> --failed`). A deploy only happens from a green run, so nothing is half-deployed. If it persists for hours, a Maven mirror or a build cache in the shared build workflow is the real fix. |
| Browser or `curl` reports the certificate as revoked or expired although `rbg-cert` renewed it | Traefik serves the old file: it only watches its config, not the certificates behind the `live/` symlinks. Check that the hook exists and is executable (`ls -l /usr/local/cert.d/50-restart-traefik`), then `sudo rbg-cert --reinstall` (runs the hook) or `sudo docker restart hestia-traefik`. If `rbg-cert` itself has no valid certificate: `sudo rbg-cert --force-request`, wait about five minutes, `sudo rbg-cert`. |
| Deploy fails with `cp: 'compose.prod.yaml' and 'compose.prod.yaml' are the same file` | The compose file sits at the repository root, where the deploy reusable copies it first. It must stay in `infra/` and the workflow's `docker-compose-file` must name that path. `scripts/deploy-preflight.sh` (job `deploy-preflight`, a required check) catches it in a pull request. |
| Deploy succeeds but Postgres has no roles, or the compose bind mount `./infra/postgres/init` fails | The files landed in a different deploy folder than the one the compose file runs from. The folder is `/opt/hestia/app` in `DEPLOY_PATH` and `deployment-base-path` of the workflow and must exist and belong to the deploy user (setup guide, steps 3 and 8). The role script only runs on an empty data directory: for a database that already holds data nothing needs repairing. |
| `hestia-server` unhealthy or restarting right after a deploy | `sudo docker logs --tail 100 hestia-server`. Usual causes: SAML key files missing or unreadable (`Private key location ... does not exist`), a failed or checksum-mismatching Flyway migration, a missing required variable, the database unreachable, the identity provider's metadata URL unreachable. |
| Deploy fails writing `.env`, or compose says `set X in .env` | A value the compose file requires has no GitHub secret or variable (names in setup guide, step 7). |
| Server refuses to start with "development login must not run" | `ENVIRONMENT=Production` together with the `local` profile, or `SPRING_PROFILES_ACTIVE` overridden. Remove the override. |
| Every login ends in an error at the identity provider | The certificate or metadata registered there no longer matches (key rotation unfinished) or the entity ID changed with `APP_HOST`. Compare the metadata at `https://<APP_HOST>/<prefix>/saml2/service-provider-metadata/tum` with what the provider holds. |
| SPA loads but API calls answer 401 | No session: the browser should have been sent to `/saml2/authenticate/tum`. Check the identity provider settings and the server log. |
| After a restart all file links of ExamLense are broken | The signing key changed or was not persistent. `EXAMLENSE_FILES_SIGNING_SECRET` is required; the compose file refuses to start without it, so a hand-made `.env` is incomplete. |
| `pull` says `denied` or `unauthorized` | GHCR login expired; see "GHCR access". |
| Smoke test fails on `UseBASE64Intrinsics` | The server started without `-XX:-UseBASE64Intrinsics` (entrypoint changed). See the security note in section 2. |
| Disk full | `df -h`, `sudo docker system df`. Prune images (`sudo docker image prune -f`), move old dumps off the VM. Never delete the Postgres volume. |
| Large uploads fail with a 502 after about a minute | Traefik's read timeout. `infra/traefik/compose.yaml` raises it to 600 s on `websecure`; a proxy started from an older copy of that file needs the setting. |
| Traefik answers 404 for a client | The client container is not on `hestia-edge`, or `APP_HOST` does not match the requested host. `sudo docker network inspect hestia-edge` lists the attached containers. |

## 7. Intake export

Uploaded course material from the intake page (`/start`) is stored in the database. There is
deliberately no admin web page to read it: only someone with shell access to the VM can export it.
`infra/scripts/export-intake.sh` writes one folder per submission with its files and a
`submission.json` (who submitted it, which terms version and hash, which consents, when, and the
files with size and SHA-256).

The VM has no repository checkout, so copy the script there first:

```bash
scp infra/scripts/export-intake.sh <you>@<APP_HOST>:/tmp/
```

On the VM, run it as root (it uses `docker exec` on `hestia-postgres` and writes with `umask 077`):

```bash
sudo bash /tmp/export-intake.sh [target-dir] [since]
#   target-dir  default: ./intake-export-<UTC timestamp>
#   since       only submissions created on or after this date, for example 2026-12-01
sudo bash /tmp/export-intake.sh /root/intake-export 2026-12-01
```

Then copy the folder off the VM (`scp -r`) and delete it there. The exported data are personal data
and copyrighted course material: handle them like the database dumps. A different container name
can be set with `HESTIA_POSTGRES_CONTAINER`.

## 8. Quick reference

```bash
hc ps                                              # state
sudo docker logs --tail 100 hestia-server          # server log
hc pull && hc up -d                                # refresh with the tag in .env
hc stop hestia-server                              # stop writes during a restore
ls -lh /opt/hestia/backups/hestia                  # backups
sudo rbg-cert --reinstall                          # certificate hook
gh workflow run "hestia CI/CD" --ref main -f image-tag=sha-<hash>   # deploy / rollback
```

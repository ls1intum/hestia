# Deployment

The landing page is a **single static container**: nginx serving the Vite build. No
backend, no database, no secrets.

- **web** — nginx with the built SPA

It publishes no host port. TLS and ports 80/443 are owned by the **shared Traefik proxy**
(`infra/traefik/`, see its README). Unlike the other apps, the landing page has **no path
prefix**: its router matches the bare host (``Host(`${APP_HOST}`)``) at **priority 1**, so it
serves `https://<host>/` while every app's `Host && PathPrefix` rule (`/examlense`,
`/learninggoalhub`, …) takes precedence. No strip middleware is needed and the Vite base
stays `/`.

Local dev: `npm install && npm run dev` → http://localhost:8090/.

## Target VMs

Chair Hestia VMs, reachable only via the **LRZ VPN** over SSH with your own TUM identifier:

- `hestia-test.aet.cit.tum.de` — test
- `hestia.aet.cit.tum.de` — prod

The shared one-time VM setup (RBG cert, `hestia-edge` network, Traefik) is already done —
see `apps/learninggoalhub/DEPLOY.md` and `infra/traefik/README.md`.

## CI/CD

The image is built by GitHub Actions and pushed to **GHCR**, then deployed onto the VM via
the shared ls1intum reusable workflows (`.github/workflows/landing-page-cicd.yml`):

| Trigger | Image built | Deploy |
| --- | --- | --- |
| Pull request | `…/landing-page:pr-<N>` | — (build only, validation) |
| Push to `main` | `…:latest` | → **Staging** (automatic) |
| GitHub Release `vX.Y.Z` | `…:vX.Y.Z` | → **Production** (waits for approval) |
| Manual (`workflow_dispatch`) | per branch | → chosen environment/tag |

The image is `ghcr.io/ls1intum/hestia/landing-page:<tag>`, built from
`apps/landing-page` only.

The GitHub `Staging`/`Production` environments and the VM deploy user already exist from
the other apps. The only per-environment values this app reads from them are:

- **Variables:** `APP_HOST` (the VM's FQDN, must match the TLS cert SAN)
- plus the shared connection secrets (`VM_HOST`, `VM_USERNAME`, `VM_SSH_PRIVATE_KEY`,
  org-level `DEPLOYMENT_GATEWAY_*`) that every app inherits.

Build-time config (`VITE_NEWSLETTER_ENDPOINT`, `VITE_NEWSLETTER_LIST_UUID`,
`VITE_NEXTCLOUD_UPLOAD_URL`, `VITE_CONTACT_EMAIL`, `VITE_ENVIRONMENT`, see `src/config.ts`)
now defaults to the real production values, so no overrides are required. Any override must
be provided at **image build** (Vite bakes them in), not deploy. Set
`VITE_ENVIRONMENT=production` on the Production build to hide the top-left test-system
banner (it shows for every other value).

nginx serves the SPA at `/` and **302-redirects any unknown path to `/`** (real files and
`/assets/*` still serve; the Impressum is a hash route so no deep server path exists). App
prefixes (`/examlense`, `/learninggoalhub`, …) are routed by Traefik before nginx and are
unaffected.

### Manual deploy / fallback

If CI is unavailable, deploy by hand from a checkout of the repo on the VM (the image must
already be in GHCR; `docker login ghcr.io` first if the package is private):

```bash
cd <repo>/apps/landing-page
printf 'APP_HOST=%s\nIMAGE_TAG=%s\n' "$(hostname -f)" latest > .env
sudo docker compose -f compose.prod.yaml --env-file .env pull
sudo docker compose -f compose.prod.yaml --env-file .env up -d
```

The page is then served at `https://<APP_HOST>/`.
`docker` needs `sudo` on these VMs unless your user is in the `docker` group.

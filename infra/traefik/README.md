# Shared edge proxy (Traefik)

One Traefik container per VM terminates TLS and routes by URL path to each thesis app.
It is the **only** thing the apps share. Everything else — database, server, client
(web) container, SAIA key, `.env` — is private to each app. App containers never join each
other's networks.

```
https://<vm-host>/<app-prefix>/...  ──▶  Traefik (:80/:443, RBG cert)
                                          └─ strips /<app-prefix>, forwards to that app's web container
```

## One-time VM setup

```bash
# The shared network every app's web container attaches to.
docker network create hestia-edge

# Start the proxy (certs already provisioned by `rbg-cert`, see below).
sudo docker compose -f infra/traefik/compose.yaml up -d
```

TLS uses the RBG certificate already on the VM under `/var/lib/rbg-cert/live/`
(generate/renew with `sudo rbg-cert --force-request` then `sudo rbg-cert`). The exact
filenames are referenced in `dynamic_conf.yml`; adjust them if the VM hostname differs.

## How an app registers itself

In the app's deploy compose, the `web` (nginx) service:

1. **Stops publishing host ports** — remove `ports:`; the proxy owns 80/443.
2. **Joins `hestia-edge`** (external) in addition to its own internal network.
3. **Declares routing labels** (pick a unique `<prefix>` and router name per app):

```yaml
    expose:
      - "80"
    networks:
      - default          # private: reach this app's own server/db
      - hestia-edge      # shared: receive traffic from Traefik
    labels:
      - "traefik.enable=true"
      - "traefik.docker.network=hestia-edge"
      - "traefik.http.routers.<app>.rule=Host(`<vm-host>`) && PathPrefix(`/<prefix>`)"
      - "traefik.http.routers.<app>.entrypoints=websecure"
      - "traefik.http.routers.<app>.tls=true"
      - "traefik.http.routers.<app>.middlewares=<app>-strip"
      - "traefik.http.middlewares.<app>-strip.stripprefix.prefixes=/<prefix>"
      - "traefik.http.services.<app>.loadbalancer.server.port=80"

networks:
  hestia-edge:
    external: true
```

Traefik strips `/<prefix>` before forwarding, so the app's nginx stays prefix-agnostic
(serves `/`, proxies `/api`). It does, however, send `X-Forwarded-Prefix: /<prefix>` so
the server can advertise correct absolute URLs (e.g. Swagger). The app's client must
be **built** with that base path (asset base, router basename, API base) — see the
learninggoalhub client for a worked example.

Prefixes in use: `/learninggoalhub`, `/examlense`. Add yours here when you onboard.

**Naming on the shared network:** Compose registers a service's *name* as a DNS alias on
every network it joins. Generic service names (`server`, `web`, `postgres`) on `hestia-edge`
therefore shadow those names for **all** attached containers — e.g. another app's nginx
resolving its upstream `server` may suddenly get your container. If a service joins
`hestia-edge`, give it an app-unique name (e.g. `examlense-server`), and when resolving
your *own* containers from a service that sits on the shared network, use the unique
`container_name` instead of the generic service name.

One exception: `apps/landing-page` serves the **bare host root** (`https://<vm-host>/`).
Its router matches ``Host(`<vm-host>`)`` with no path prefix at explicit `priority=1`, so
every app's longer `Host && PathPrefix` rule wins and the landing page catches the rest.
It needs no strip middleware and its client is built with base `/`.

# Security

What protects LearningGoalHub today and what is still open.
The configuration lives in `server/src/main/java/.../config/` (`SecurityConfig`,
`ServiceApiKeyFilter`, `LlmRateLimitFilter`) and in `client/nginx.conf`.

## At a glance

| Area | State |
| --- | --- |
| Browser login | TUM login (SAML) required on `/api/**`; hestia-test and local runs use a dev user instead. |
| Service-only endpoints | One `X-Api-Key` per consuming server, required in deployments (`ENFORCE_SERVICE_KEYS`). |
| Model calls | At most 60 requests that start model calls per client address and 10 minutes. |
| Uploads | PDF only, checked by extension and file signature; page and text limits. |
| ExamLens input | At most 100 blocks per request and 20,000 characters per block description. |
| CSV export | Cells that a spreadsheet would run as a formula are neutralised. |
| Client | Content Security Policy and hardening headers from nginx. |
| CSRF | Off; open item now that a login session exists (see below). |
| Secrets | Server-side only, from environment variables and a read-only mount. |
| Database | No host port; reachable only from the app's containers. Daily `pg_dump`. |

## Browser login

The server is a SAML service provider for TUM login (`saml2Login` in `SecurityConfig`), and
`/api/**` needs a login or a consuming server's key. Without either, the API answers `401`, and
the client shows a "Log in with TUM" screen that starts the SAML login. Swagger UI needs the login
too; the OpenAPI document at `/v3/api-docs` and `/actuator/health` stay public on purpose.

TUM's identity provider only knows the production host. hestia-test and local runs use the
`local` profile instead, where `DevAuthFilter` signs every request in as `dev-local-user`. The
deploy sets the profile from its target (`Staging` is a profile group for `local`, see
[DEPLOY.md](../DEPLOY.md#cicd)), so hestia-test needs no manual step and production keeps the
login. hestia-test is reachable only inside the university network.

Courses have no owner column, so every logged-in user sees and edits every course. The rate
limit, the upload limits and the daily backup (see [DEPLOY.md](../DEPLOY.md#backups)) still limit
the damage a single account can do.

## CSRF

CSRF protection is still off. A CSRF attack makes a logged-in user's browser send a request that
carries their session cookie, for example a form on another site that posts to the API. Browsers
that treat cookies without a `SameSite` attribute as `Lax` do not send the session cookie on such
cross-site posts, but not every browser does. Turn it on as the next step: a cookie token
(`CookieCsrfTokenRepository`) that the client echoes in an `X-XSRF-TOKEN` header on every write,
with requests carrying a service key exempt because their callers are servers.

## Service keys

ExamLens and Workshopper each get their own key, stored as a deployment secret on both sides.
`ServiceApiKeyFilter` compares the `X-Api-Key` header against every configured key in constant
time and, on a match, treats the request as that caller for this request only. A blank key is
never configured, so an unset secret cannot match an empty header. The two service-only
endpoints, the rollout and the error codes are in [api.md](api.md#service-keys).

A valid key also passes the login check on every other `/api/**` endpoint, because the consuming
servers have no browser session (Workshopper lists the courses this way). `ENFORCE_SERVICE_KEYS`
defaults to `true` in `compose.prod.yaml`: the service-only endpoints then accept only a key, not
a TUM login. With it off, any logged-in caller reaches them and the log shows `Would reject ...`.
A key is a server secret and must never end up in a browser bundle.

## Rate limit on model calls

Every model call spends the deployment's shared inference quota and can run for minutes.
`LlmRateLimitFilter` counts the requests in `SecurityConfig.LLM_REQUESTS` (extraction, Topic
tree rebuild, upload, exam goals, "Find in the slides", "Generate with AI") per client address
and answers the 61st within 10 minutes with `429` and a `Retry-After` header. Requests with a
valid service key are not counted, because all users of a consuming app share its server's
address.

The client address comes from the forwarded headers
(`server.forward-headers-strategy: framework`), so the count is only as reliable as the proxy
that sets them. The counters live in memory: they reset on restart and are not shared between
server instances. Both limits are settings (`hestia.rate-limit.*`).

## Input limits

| Input | Limit | Answer |
| --- | --- | --- |
| Upload request | 100 MB per file, 500 MB per request (Spring and nginx) | `413` |
| File type | `.pdf` extension and a `%PDF-` signature in the first kilobyte | `415` |
| One PDF | 2,000 pages and 3,000,000 characters of text (`hestia.upload.*`) | `413` |
| Course name | 255 characters | `400` |
| Exam request | 100 blocks, 20,000 characters per description | `400` |
| "Find in the slides" | 80 pages read per search, 500 characters per topic | `400` |

A whole-semester slide collection has about 600 pages and 350,000 characters, so the PDF limits
only stop files that would occupy the parser and the extraction for a long time.

## Output

- **CSV export.** Goal texts, labels and file names come from uploads and model replies. A cell
  starting with `=`, `+`, `-`, `@`, tab or carriage return gets a leading apostrophe, so a
  spreadsheet opens it as text (`LearningGoalCsvWriter.neutralizeFormulas`).
- **HTML.** The client renders all text through React, which escapes it; there is no
  `dangerouslySetInnerHTML`.
- **Headers.** nginx sends `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: strict-origin-when-cross-origin` and a Content Security Policy that allows
  scripts only from the app itself and no framing.
- **Errors.** Every error body is `{code, message}` (`GlobalExceptionHandler`); an unexpected
  exception is logged and answered with a generic `500`, without a stack trace.

## Secrets and data

- The Logos API key, the database password and the service keys are GitHub Actions secrets. The
  deploy workflow writes them to a `.env` file on the VM; nothing in the repository or the client
  bundle contains them. See [DEPLOY.md](../DEPLOY.md#cicd).
- The SAML key pair is mounted read-only from `/opt/hestia/secrets/saml`.
- The repository's pre-commit hook and CI run gitleaks on every change.
- Postgres publishes no host port. Queries go through Spring Data and JPQL with bound
  parameters; there is no SQL built from strings. Hibernate only validates the schema, and Flyway
  owns every change to it.
- Uploaded PDFs are stored in the database (`document_content`) so the source pane can show the
  quoted page. Deleting a course deletes them.

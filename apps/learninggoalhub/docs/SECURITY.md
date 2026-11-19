# Security

What protects LearningGoalHub today and what is still open.
The configuration lives in `server/src/main/java/de/tum/cit/hestia/core/internal/security/` (the one
security chain: SAML login, CSRF, `LlmRateLimitFilter`), in `materials/course/` (course roles) and in `client/nginx.conf`.

## At a glance

| Area | State |
| --- | --- |
| Browser login | TUM login (SAML) required on `/learninggoalhub/**`; hestia-test and local runs use a dev user instead. |
| Other modules | `assessment` and `teaching` call `materials` in-process (ports in `core.spi`); there is no service key. |
| Model calls | Off by default. `HESTIA_RATE_LIMIT_LLM_REQUESTS` (a positive number) limits the requests that start model calls per client address and 10 minutes. |
| Uploads | PDF only, checked by extension and file signature; page and text limits. |
| ExamLens input | At most 100 blocks per request and 20,000 characters per block description. |
| CSV export | Cells that a spreadsheet would run as a formula are neutralised. |
| Client | Content Security Policy and hardening headers from nginx. |
| CSRF | On: cookie token `XSRF-TOKEN`, echoed in `X-XSRF-TOKEN` on every write (see below). |
| Tenancy | Every table has `org_id` with row-level security; an organisation sees only its own rows. |
| Course roles | `VIEWER` reads, `EDITOR` changes content and starts model calls, `OWNER` also deletes the course. |
| Secrets | Server-side only, from environment variables and a read-only mount. |
| Database | No host port; reachable only from the app's containers. Daily `pg_dump`. |

## Browser login

The server is a SAML service provider for TUM login (`saml2Login` in `core`), and
`/api/**` needs a login. Without one, the API answers `401`, and
the client shows a "Log in with TUM" screen that starts the SAML login. Swagger UI needs the login
too; the OpenAPI document at `/v3/api-docs` and `/actuator/health` stay public on purpose.

TUM's identity provider only knows the production host. hestia-test and local runs use the
`local` profile instead, where `DevAuthFilter` signs every request in as `dev-local-user`. The
deploy sets the profile from its target (`Staging` is a profile group for `local`, see
[DEPLOY.md](../DEPLOY.md#cicd)), so hestia-test needs no manual step and production keeps the
login. hestia-test is reachable only inside the university network.

A user belongs to an organisation (the login's `schacHomeOrganization`). Every materials course
belongs to a `core.course`, where the creator is `OWNER` and members are managed. Everything under
`/api/courses/{courseId}/**` is checked against the caller's role there; a course of another
organisation or without membership answers `404`, a role that is too small `403`
(`CourseAccessInterceptor`). Background work (extraction, "Find in the slides", the Topic tree) runs
with the organisation of the request that started it (`OrgExecutors`). Intake submissions stay filed under
the uploader's external id and are visible to that user only.

## CSRF

CSRF protection is on. A CSRF attack makes a logged-in user's browser send a request that
carries their session cookie, for example a form on another site that posts to the API. The server sets a
cookie token (`XSRF-TOKEN`) and rejects every write without the same value in the `X-XSRF-TOKEN`
header; the client's fetch wrapper (`src/api/client.ts`) and the intake upload send it.

## Rate limit on model calls

Every model call spends the deployment's shared inference quota and can run for minutes.
`LlmRateLimitFilter` counts the requests in `CoreSecurityConfig.LLM_REQUESTS` (extraction, Topic
tree rebuild, upload, exam goals, "Find in the slides", "Generate with AI") per client address
and answers the 61st within 10 minutes with `429` and a `Retry-After` header.

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

- The Logos API key, the database password are GitHub Actions secrets. The
  deploy workflow writes them to a `.env` file on the VM; nothing in the repository or the client
  bundle contains them. See [DEPLOY.md](../DEPLOY.md#cicd).
- The SAML key pair is mounted read-only from `/opt/hestia/secrets/saml`.
- The repository's pre-commit hook and CI run gitleaks on every change.
- Postgres publishes no host port. Queries go through Spring Data and JPQL with bound
  parameters; there is no SQL built from strings. Hibernate only validates the schema, and Flyway
  owns every change to it.
- Uploaded PDFs are stored in the database (`document_content`) so the source pane can show the
  quoted page. Deleting a course deletes them.

# Examlense (assessment module of the hestia server)

The `assessment` module of the one hestia Spring Boot server (`server/`, package
`de.tum.cit.hestia.assessment`) is the backend of Examlense. It owns **everything** of the app:
CRUD + ownership checks, the AI parse/solve pipeline, file storage, realtime, and parse
metrics — in its own schema `assessment` of the shared **PostgreSQL** database. All its
endpoints are served under the URL prefix `/examlense` (`/examlense/api/...`); the paths in
this document are relative to that prefix unless they carry it. Course lookup and goal
derivation use the `GoalDirectory` / `ExamGoalDerivation` ports of the materials module,
in process.

- **Auth** — a static bearer token (`API_AUTH_TOKEN`) plus a per-IP rate
  limiter. Single-user for now: `owner_id`/`user_id`/`graded_by` and storage
  paths are stamped server-side from the default principal. (Real per-user auth
  is a later, separate effort.)
- **DB** — PostgreSQL 16; schema via Flyway (`src/main/resources/db/migration/`),
  validated at boot (`ddl-auto: validate`).
- **Storage** — local filesystem behind `StorageService`, served through
  HMAC-signed, time-limited URLs (`GET /api/files/**`, public + signature-gated).
  URLs are signed with a dedicated key (`FILES_SIGNING_SECRET`); when unset the
  server falls back to the auth token and logs a warning.
- **Realtime** — SSE (`GET /api/exams/{id}/events`, `GET /api/exams/events`);
  the token rides as a `?token=` query param since `EventSource` can't set headers.
  The hub sends a keep-alive comment every 25s so proxies don't drop idle streams.

## API documentation

The endpoint surface is documented by a generated OpenAPI spec rather than by
hand here, so it cannot drift from the code:

- **Spec** — `GET /v3/api-docs/assessment` (springdoc group `assessment`; it holds only the
  `/examlense/...` paths)
- **Swagger UI** — [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html), group `assessment`

Both are **off by default** for this module and enabled by the `local` profile, so a normal
`SPRING_PROFILES_ACTIVE=local ./gradlew :server:bootRun` has them. With docs off, the
assessment paths are removed from every OpenAPI document the server still serves for the other
modules, and the `assessment` group does not exist. To browse them on a deployed instance, set
`API_DOCS_ENABLED=true` — note they are unauthenticated when enabled (Swagger UI can't present
a bearer token on its initial page load), and the frontend's `nginx.conf` would need a
`location` block for `/v3/api-docs` and `/swagger-ui` to route them at all.

Click **Authorize** in Swagger UI and paste the value of `API_AUTH_TOKEN` to
make "Try it out" work. To grab a point-in-time copy of the spec:

```bash
curl -s http://localhost:8080/v3/api-docs/assessment -o openapi.json
```

`OpenApiDocsTest` keeps the spec honest — every operation must carry a summary
and a tag, and the authenticated principal must not leak in as a parameter.

This API is **internal to ExamLense** — no other app consumes it, and the spec
carries no compatibility guarantee. Do not treat it as a stable interface. See
[`../DEPLOY.md`](../DEPLOY.md) for what actually protects it in production.

## Requirements

- JDK 21 (e.g. `brew install openjdk@21` or `sdk install java 21-tem`)
- Docker — for PostgreSQL 16 (database `hestia` with the roles from
  `infra/postgres/init/01-roles.sql`) and for the test suite, which uses Testcontainers.
- The Gradle wrapper at the repository root (`./gradlew`, Gradle 9.4.1) — do not install Gradle
  separately. The server is the project `:server` of the root Gradle build.

## Configure

1. Copy `server/.env.example` to `server/.env` and fill in the AI provider values you want to
   use. GPT strategies use `OPENAI_API_KEY`; Claude strategies use
   `ANTHROPIC_API_KEY`; Gemini strategies use `GEMINI_API_KEY`. `API_AUTH_TOKEN` defaults to
   `dev-local-token` and must match the client's `VITE_API_AUTH_TOKEN`.
   Optional hardening: `FILES_SIGNING_SECRET` (dedicated HMAC key for signed
   file URLs — recommended in deployments so token rotation doesn't invalidate
   outstanding URLs) and `API_RATELIMIT_BEHIND_PROXY=true` (trust
   `X-Forwarded-For` for rate limiting — only when behind a reverse proxy).
2. Load the env vars into your shell (Spring Boot does **not** auto-read `.env`):

   ```bash
   set -a; source .env; set +a
   ```

3. Provide SAML signing credentials. The SP config in `application.yml` is loaded
   at startup whether or not you use SSO, so **without these the app will not
   boot** — it fails with `Private key location 'class path resource
   [certs/private-key.pem]' does not exist`. The real credentials are secrets and
   the paths are gitignored, so generate a throwaway self-signed pair for local
   work (from the repository root):

   ```bash
   mkdir -p server/src/main/resources/certs
   openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
     -keyout server/src/main/resources/certs/private-key.pem \
     -out    server/src/main/resources/certs/certificate.crt \
     -subj "/CN=examlense-local"
   ```

   These are only good enough to boot the app; TUM SSO will not accept them. Point
   `SAML_PRIVATE_KEY_LOCATION` / `SAML_CERTIFICATE_LOCATION` elsewhere (they accept
   `file:` URLs) if you keep your credentials outside the source tree. The test
   suite is unaffected — it mocks `RelyingPartyRegistrationRepository`.

## Run locally

```bash
# from the repo root, with Postgres running (see Requirements)
set -a; source server/.env; set +a
SPRING_PROFILES_ACTIVE=local ./gradlew :server:bootRun
```

The one server listens on `http://localhost:8080` (override with `PORT`); the Vite dev
server of the client uses `:5173`. The DB connection defaults to `localhost:5432`, database
`hestia` (`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`).

The `local` profile (`application-local.yml`) turns on the API docs and the development
login (it signs in as `dev-local-user`, bypassing SAML — never use it in production).
Learning goals come from the materials module of the same server; there are no
LearningGoalHub settings any more.

### Smoke test

```bash
# Public — no token needed
curl http://localhost:8080/examlense/api/healthz
# => {"status":"ok","time":"..."}

# Protected — static bearer token (matches API_AUTH_TOKEN)
curl -H "Authorization: Bearer dev-local-token" http://localhost:8080/examlense/api/exams
# => []   (empty list on a fresh DB)
```

A 401 means the `Authorization` header is missing or the token doesn't match
`API_AUTH_TOKEN`.

Under the `local` profile you can also browse the whole API at
<http://localhost:8080/swagger-ui.html> (group `assessment`) — see [API documentation](#api-documentation).

## Tests

```bash
./gradlew :server:test --tests 'de.tum.cit.hestia.assessment.*'   # needs a running Docker daemon
```

This is the same suite CI runs as a hard gate (`.github/workflows/examlense-cicd.yml`);
a red run blocks every deploy.

The classes that extend `AbstractIntegrationTest` use `TestDatabase`: a real `postgres:16`
container via Testcontainers (one container for the whole JVM run, set up like production) —
hence the Docker requirement. Everything else is plain JUnit 5 unit tests.

There is **no unit-only task**, and filtering by name doesn't cleanly separate the two:
`SmokeContextTest`, `SecurityRulesSmokeTest`, and `HealthSmokeTest` all need Docker despite
the `Test` suffix. If you must skip the container tests, list them explicitly with
`--tests`, but the honest answer is to start Docker.

`check` is equivalent to `test` — no Checkstyle, Spotless, or SpotBugs
is configured.

## Client wiring

The Vite project's `.env` (or `.env.local`) needs:

```
VITE_API_BASE_URL=http://localhost:8080/examlense
VITE_API_AUTH_TOKEN=dev-local-token
```

`client/src/lib/api/api-client.ts` is the single transport; it sends the static token on
every request, and `client/src/lib/api/sse.ts` opens the SSE streams.

## Project layout

```
server/
├── build.gradle.kts            # project :server of the root build (all modules)
├── src/main/java/de/tum/cit/hestia/assessment/   # this module; package-by-feature: each
│                               #   domain slice owns its controller + service + entity +
│                               #   repository + DTOs (the paths below are relative to it)
│   │
│   │   # ── domain feature slices ──
│   ├── exam/            # ExamController, ExamService (duplication), ExamProgressService,
│   │                    #   Exam entity + repo, ExamDtos (snake_case)
│   ├── section/         # Section/Block/Figure controllers, SectionService (insert/unconfirm/
│   │                    #   delete), Section/SectionBlock/SectionFigure entities + repos, SectionDtos
│   ├── task/            # Task + TaskAnswer controllers, TaskService (insert),
│   │                    #   Task/TaskOption/TaskAnswer entities + repos, TaskDtos
│   ├── grading/         # TaskGradeController, TaskGrade entity + repo, GradeDto
│   ├── parse/           # parse pipeline: ParseExamService (orchestrator), ParseInputBuilder,
│   │                    #   ParsedExamPersister, ParseProgress, ParseMetricsController
│   ├── solve/           # SolveExam/Section/Task services + SolveCore (shared machinery)
│   ├── lgh/             # LearningGoalHub proxy + goal generation
│   │
│   │   # ── shared technical infrastructure ──
│   ├── ai/              # AiProvider + factory, ProviderHttpCaller (shared transport/retry),
│   │                    #   per-provider request/response adapters, parser/solver strategies
│   ├── storage/         # StorageService, LocalFileSystemStorageService, SignedUrls, FileController
│   ├── sse/             # SseHub + SseController
│   ├── security/        # StaticTokenAuthFilter, RateLimitFilter, CurrentUser
│   ├── config/          # SecurityConfig, AsyncConfig (solver/LGH pools, scheduling),
│   │                    #   OpenApiConfig (spec metadata; only loaded when docs are on)
│   ├── error/           # ApiException + GlobalExceptionHandler + ErrorResponse (spec schema)
│   ├── prompts/         # solver prompt + submit_answers schema
│   ├── models/          # ModelsController (parser/solver model catalog endpoint)
│   ├── health/          # HealthController
│   └── shared/          # Access + Patch (ownership / PATCH-body helpers), DefaultUser
└── src/main/resources/
    ├── application.yml
    └── db/migration/    # Flyway (V1__baseline.sql … V11)
```

## Data model

Flyway owns the schema (`src/main/resources/db/migration/`, V1…V11); Hibernate runs
`ddl-auto: validate` and only checks entity ↔ table parity. Eight live tables:

```
exams ──< sections ──< section_blocks ──< section_figures      (all ON DELETE CASCADE)
  │          ╵
  │          └╌╌ tasks.section_id                              (ON DELETE SET NULL)
  │
  ├──< tasks ──< task_answers                                  (CASCADE from tasks and exams)
  │        └──1 task_grades                                    (UNIQUE task_id — one grade per task)
  │
  └──< parse_metrics                                           (ON DELETE SET NULL)
```

Three things that surprise people:

- **`tasks.section_id` is `SET NULL`, not cascade.** Deleting a section orphans its tasks at
  the DB level; `SectionService` does the app-level cleanup instead, rather than trusting the
  client to call the delete-by-section endpoints first.
- **`parse_metrics.exam_id` is `SET NULL`** on purpose, so parser-quality metrics outlive the
  exams they were measured on.
- **`owner_id` / `user_id` / `graded_by` are plain uuid columns with no foreign key** — there
  is no users table. They are stamped from `DefaultUser.ID` (see the auth note above).

`exams.status` is CHECK-constrained to `draft|parsing|failed|ready|evaluating|grading|finished`,
mirrored in Java in `ExamController`. `updated_at` is trigger-maintained on `exams`, `sections`,
`tasks`, `section_blocks`, and `task_grades` — `section_figures` and `task_answers` have only
`created_at`.

## AI provider configuration

Parser/solver strategies in `server/src/main/java/de/tum/cit/hestia/assessment/ai/ParserStrategies.java` and
`SolverStrategies.java` pin a provider transport. Configure only the providers
you want to use; selecting a model without its key returns a clear provider
configuration error.

```
# OpenAI GPT models
OPENAI_API_KEY=<your OpenAI key>
# OPENAI_BASE_URL=https://api.openai.com/v1

# Anthropic Claude models
ANTHROPIC_API_KEY=<your Anthropic key>
# ANTHROPIC_BASE_URL=https://api.anthropic.com

# Google Gemini models
GEMINI_API_KEY=<your Gemini key>
```

Available models include Gemma / Mistral / Qwen, GPT, Claude, and Gemini entries
(see the UI dropdown and `client/src/lib/exam/llm-models.ts`).

### PDF parsing: model choice + fallback

The parser model is chosen entirely server-side — the client only triggers a parse
(plus the fast-mode flag). `parse-exam-pdf` uses the default parser
(`ParserStrategies.DEFAULT_ID` = `gemini-3.5-flash`) and, if that call fails
**transiently** (busy/429, unreachable/timeout, provider 5xx, or a missing/invalid
key → 500 — see `AiExceptions.isTransient`), transparently retries the same PDF once
with `ParserStrategies.FALLBACK_ID` (`gpt-5.5`), honoring the user's fast-mode choice.
Non-transient failures (unstructured/malformed output, out-of-credits) are not retried.
The fallback therefore requires a valid `OPENAI_API_KEY`. Exactly one `parse_metrics`
row is written per parse, stamped with the model that actually served; on a successful
fallback the exam's `parser_model` is updated to the serving model too.

## Deploying

This module is deployed as part of the `hestia-server` container (one image for all modules, built by CI). The local-FS storage directory
(`STORAGE_LOCAL_BASE_PATH`) is backed by a named Docker volume and Postgres runs as a sibling
container; provider keys, `API_AUTH_TOKEN`, and `DB_*` come from the deploy `.env`.
See the app-level **[`../DEPLOY.md`](../DEPLOY.md)** for the full pipeline.

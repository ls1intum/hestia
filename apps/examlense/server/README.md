# Examlense Spring Boot server

The single service behind Examlense. It owns **everything**: CRUD + ownership
checks, the AI parse/solve pipeline, file storage, realtime, and parse metrics —
talking to a plain **PostgreSQL** database. (Supabase has been fully removed.)

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

Endpoint surface: `/api/healthz`, `/api/me`, `/api/parser-models`, `/api/solver-models`,
`POST /api/parse-exam-pdf` (async), `POST /api/solve-task|solve-section|solve-exam`,
full CRUD under
`/api/exams|sections|tasks|blocks|figures` (+ `duplicate`, `cancel`,
`confirm`/`unconfirm`, delete-by-section), `/api/task-grades`,
`/api/parse-metrics`,
the LGH proxy (`/api/lgh/courses`, `/api/exams/{id}/learning-goals`), and the
SSE/file endpoints above.

This API is **internal to ExamLense** — no other app consumes it, and there is no
published contract (no springdoc/OpenAPI). Do not treat it as a stable interface.
See [`../DEPLOY.md`](../DEPLOY.md) for what actually protects it in production.

## Requirements

- JDK 21 (e.g. `brew install openjdk@21` or `sdk install java 21-tem`)
- Docker — for PostgreSQL (`docker compose up -d postgres` from `apps/examlense/`)
  and for the test suite, which uses Testcontainers.
- The Gradle wrapper (`./gradlew`, Gradle 8.10) — do not install Gradle separately.

## Configure

1. Copy `.env.example` to `.env` and fill in the AI provider values you want to
   use. GWDG uses `AI_API_KEY` / `AI_BASE_URL`; GPT strategies use
   `OPENAI_API_KEY`; Claude strategies use `ANTHROPIC_API_KEY`; Gemini
   strategies use `GEMINI_API_KEY`. `API_AUTH_TOKEN` defaults to
   `dev-local-token` and must match the client's `VITE_API_AUTH_TOKEN`.
   Optional hardening: `FILES_SIGNING_SECRET` (dedicated HMAC key for signed
   file URLs — recommended in deployments so token rotation doesn't invalidate
   outstanding URLs) and `API_RATELIMIT_BEHIND_PROXY=true` (trust
   `X-Forwarded-For` for rate limiting — only when behind a reverse proxy).
2. Load the env vars into your shell (Spring Boot does **not** auto-read `.env`):

   ```bash
   set -a; source .env; set +a
   ```

## Run locally

```bash
# from the repo root: start Postgres first
docker compose up -d postgres

cd server
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Server listens on `http://localhost:8081` (override with `PORT`). The Vite dev
server uses `:8080`, so they coexist without a port clash. The DB connection
defaults to `localhost:5433` (the Docker Postgres host port).

The `local` profile (`application-local.yml`) points LearningGoalHub at its
public web URL instead of the Docker-internal hostname used on the shared VM, so
goal derivation works from a host `bootRun` (on the TUM VPN — the deployed LGH is
VPN-only). Omit `SPRING_PROFILES_ACTIVE=local` and the app falls back to the
`LGH_BASE_URL` env var / default.

### Smoke test

```bash
# Public — no token needed
curl http://localhost:8081/api/healthz
# => {"status":"ok","time":"..."}

# Protected — static bearer token (matches API_AUTH_TOKEN)
curl -H "Authorization: Bearer dev-local-token" http://localhost:8081/api/exams
# => []   (empty list on a fresh DB)
```

A 401 means the `Authorization` header is missing or the token doesn't match
`API_AUTH_TOKEN`.

## Tests

```bash
./gradlew test     # needs a running Docker daemon
```

This is the same suite CI runs as a hard gate (`.github/workflows/examlense-cicd.yml`);
a red run blocks every deploy.

Eight classes extend `AbstractIntegrationTest`, which starts a real `postgres:16` container
via Testcontainers (one singleton container reused across the whole suite) — hence the Docker
requirement. Everything else is plain JUnit 5 unit tests.

There is **no unit-only task**, and filtering by name doesn't cleanly separate the two:
`SmokeContextTest`, `SecurityRulesSmokeTest`, and `HealthSmokeTest` all need Docker despite
the `Test` suffix. If you must skip the container tests, list them explicitly with
`--tests`, but the honest answer is to start Docker.

`./gradlew check` is equivalent to `./gradlew test` — no Checkstyle, Spotless, or SpotBugs
is configured.

## Client wiring

The Vite project's `.env` (or `.env.local`) needs:

```
VITE_API_BASE_URL=http://localhost:8081
VITE_API_AUTH_TOKEN=dev-local-token
```

`client/src/lib/api/api-client.ts` is the single transport; it sends the static token on
every request, and `client/src/lib/api/sse.ts` opens the SSE streams.

## Project layout

```
server/
├── build.gradle.kts
├── settings.gradle.kts
├── src/main/java/app/          # package-by-feature: each domain slice owns its
│   ├── ApiApplication.java     #   controller + service + entity + repository + DTOs
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
│   ├── config/          # SecurityConfig, AsyncConfig (solver/LGH pools, scheduling)
│   ├── error/           # ApiException + GlobalExceptionHandler
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

Parser/solver strategies in `src/main/java/app/ai/ParserStrategies.java` and
`SolverStrategies.java` pin a provider transport. Configure only the providers
you want to use; selecting a model without its key returns a clear provider
configuration error.

```
# GWDG / OpenAI-compatible models
AI_API_KEY=<your GWDG (or other OpenAI-compatible) key>
AI_BASE_URL=<provider base url, e.g. https://chat-ai.academiccloud.de/v1>

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

This service is deployed as the `server` container on the chair Hestia VMs (Docker image
`ghcr.io/ls1intum/hestia/examlense/server`, built by CI). The local-FS storage directory
(`STORAGE_LOCAL_BASE_PATH`) is backed by a named Docker volume and Postgres runs as a sibling
container; provider keys, `API_AUTH_TOKEN`, and `DB_*` come from the deploy `.env`.
See the app-level **[`../DEPLOY.md`](../DEPLOY.md)** for the full pipeline.

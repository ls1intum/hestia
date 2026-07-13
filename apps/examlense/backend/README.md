# Examlense Spring Boot backend

The single backend for Examlense. It owns **everything**: CRUD + ownership
checks, the AI parse/solve pipeline, file storage, realtime, and admin data —
talking to a plain **PostgreSQL** database. (Supabase has been fully removed;
see [`../docs/supabase-migration.md`](../docs/supabase-migration.md).)

- **Auth** — a static bearer token (`API_AUTH_TOKEN`) plus a per-IP rate
  limiter. Single-user for now: `owner_id`/`user_id`/`graded_by` and storage
  paths are stamped server-side from the default principal. (Real per-user auth
  is a later, separate effort.)
- **DB** — PostgreSQL 16; schema via Flyway (`src/main/resources/db/migration/`),
  validated at boot (`ddl-auto: validate`).
- **Storage** — local filesystem behind `StorageService`, served through
  HMAC-signed, time-limited URLs (`GET /api/files/**`, public + signature-gated).
  URLs are signed with a dedicated key (`FILES_SIGNING_SECRET`); when unset the
  backend falls back to the auth token and logs a warning.
- **Realtime** — SSE (`GET /api/exams/{id}/events`, `GET /api/exams/events`);
  the token rides as a `?token=` query param since `EventSource` can't set headers.
  The hub sends a keep-alive comment every 25s so proxies don't drop idle streams.

Endpoint surface: `/api/healthz`, `/api/parser-models`, `/api/solver-models`,
`POST /api/parse-exam-pdf` (async), `POST /api/solve-task|solve-section|solve-exam`,
full CRUD under
`/api/exams|sections|tasks|blocks|figures` (+ `duplicate`, `cancel`,
`confirm`/`unconfirm`, delete-by-section), `/api/task-grades`,
`/api/parse-survey`, `/api/parse-metrics`, `/api/admin/survey|survey-by-model`,
the LGH proxy (`/api/lgh/courses`, `/api/exams/{id}/learning-goals`), and the
SSE/file endpoints above.

## Requirements

- JDK 21 (e.g. `brew install openjdk@21` or `sdk install java 21-tem`)
- Docker (for PostgreSQL) — `docker compose up -d postgres` from the repo root.
- The Gradle wrapper (`./gradlew`).

## Configure

1. Copy `.env.example` to `.env` and fill in the AI provider values you want to
   use. GWDG uses `AI_API_KEY` / `AI_BASE_URL`; GPT strategies use
   `OPENAI_API_KEY`; Claude strategies use `ANTHROPIC_API_KEY`; Gemini
   strategies use `GEMINI_API_KEY`. `API_AUTH_TOKEN` defaults to
   `dev-local-token` and must match the frontend's `VITE_API_AUTH_TOKEN`.
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

cd backend
set -a; source .env; set +a
./gradlew bootRun
```

Server listens on `http://localhost:8081` (override with `PORT`). The Vite dev
server uses `:8080`, so they coexist without a port clash. The DB connection
defaults to `localhost:5433` (the Docker Postgres host port).

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

## Frontend wiring

The Vite project's `.env` (or `.env.local`) needs:

```
VITE_API_BASE_URL=http://localhost:8081
VITE_API_AUTH_TOKEN=dev-local-token
```

`src/lib/api-client.ts` is the single transport; it sends the static token on
every request, and `src/lib/sse.ts` opens the SSE streams.

## Project layout

```
backend/
├── build.gradle.kts
├── settings.gradle.kts
├── src/main/java/app/
│   ├── ApiApplication.java
│   ├── security/        # StaticTokenAuthFilter, RateLimitFilter, CurrentUser
│   ├── config/          # SecurityConfig, AsyncConfig (solver/LGH pools, scheduling)
│   ├── api/             # CRUD controllers + CrudService + Dtos (snake_case) + Access/Patch helpers
│   ├── error/           # ApiException + GlobalExceptionHandler
│   ├── persistence/     # JPA entities + repositories + DefaultUser
│   ├── parse/           # parse pipeline: ParseExamService (orchestrator),
│   │                    #   ParseInputBuilder, ParsedExamPersister, ParseProgress, metrics
│   ├── solve/           # SolveExam/Section/Task services + SolveCore (shared machinery)
│   ├── ai/              # AiProvider + factory, ProviderHttpCaller (shared transport/retry),
│   │                    #   per-provider request/response adapters, parser/solver strategies
│   ├── lgh/             # LearningGoalHub proxy + goal generation
│   ├── prompts/         # solver prompt + submit_answers schema
│   ├── storage/         # StorageService, LocalFileSystemStorageService, SignedUrls
│   └── sse/             # SseHub + SseController
└── src/main/resources/
    ├── application.yml
    └── db/migration/    # Flyway (V1__baseline.sql … V5)
```

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
(see the UI dropdown and `src/lib/llm-models.ts`).

## Deploying

The backend is deployed as the `server` container on the chair Hestia VMs (Docker image
`ghcr.io/ls1intum/hestia/examlense/server`, built by CI). The local-FS storage directory
(`STORAGE_LOCAL_BASE_PATH`) is backed by a named Docker volume and Postgres runs as a sibling
container; provider keys, `API_AUTH_TOKEN`, and `DB_*` come from the deploy `.env`.
See the app-level **[`../DEPLOY.md`](../DEPLOY.md)** for the full pipeline.

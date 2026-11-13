# ExamLense

React 18 + TypeScript SPA for **exam authoring, AI-powered parsing, solving, grading, and results analysis**. Authors upload an exam PDF (or build one from scratch), an LLM parses it into structured tasks, solves them, the author grades the AI's answers, and a results dashboard breaks down performance — including per **Learning Goal**.

## Stack

- **Client** — Vite (SWC), React 18, TypeScript, Tailwind (HESTIA design system), shadcn/ui (Radix), React Query, React Router v6, Recharts. English-only; there is no i18n layer.
- **Spring Boot server** (`server/`, JDK 21) — the only service. Owns all CRUD + ownership, parse/solve/metrics endpoints, file storage (local filesystem + HMAC-signed URLs), and SSE realtime. Auth is a static bearer token (single-user). See [`server/README.md`](server/README.md).
- **PostgreSQL** — plain Postgres (Docker, host port 5433); schema managed by Flyway in `server/src/main/resources/db/migration/`.
- **AI providers** — native OpenAI, Anthropic, and Gemini APIs for the GPT / Claude / Gemini strategies, each enabled by its own key. The model catalog lives in `client/src/lib/exam/llm-models.ts`.

> Supabase (auth, Postgres+RLS, storage, realtime, edge functions) has been fully removed.

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 21 | the Spring Boot server |
| Node | 22 | the Vite client |
| Docker | any recent | local Postgres **and** the server test suite (Testcontainers) |
| Gradle | 9.4.1 | the wrapper at the repository root (`./gradlew`) — do not install it separately |

Ports in use: **8080** server (the one hestia server; ExamLense is served under `/examlense`), **5173** Vite dev server, **5432** Postgres.

## Running locally

The full local stack is **three processes**:

### 1. PostgreSQL (Docker)

A PostgreSQL 16 with the database `hestia` and the roles from `infra/postgres/init/01-roles.sql`
(host port 5432 by default; `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` override).
Flyway applies the schema automatically when the server boots.

### 2. Server

```bash
# from the repository root
set -a; source server/.env; set +a   # Spring Boot does not auto-read .env
SPRING_PROFILES_ACTIVE=local ./gradlew :server:bootRun   # http://localhost:8080/examlense
```

With the `local` profile the API is browsable at
<http://localhost:8080/swagger-ui.html> (group `assessment`) — see [API documentation](#api-documentation) below.
See [`server/docs/assessment.md`](../../server/docs/assessment.md) for configuration and a smoke test.

### 3. Client

```bash
cd client
npm install                   # .npmrc already sets legacy-peer-deps
npm run dev                   # http://localhost:5173
```

### Learning goals are optional

Learning goals come from the **materials** module of the same server (in process, no separate
service or settings). Linking a course when you create an exam is optional, and nothing breaks
without it: if the course lookup fails, the course picker shows "LearningGoalHub is
unreachable — you can continue without a course", section confirmation still succeeds, and
`/examlense/api/lgh/courses` returns 502 while the rest of the app works normally. You only lose
the goal insights in the grading and results views.

## API documentation

The server API is documented by a generated **OpenAPI spec** — spec at `/v3/api-docs/assessment`,
Swagger UI at `/swagger-ui.html`. Both are enabled by the `local` profile and off in
deployments, so run the server with `SPRING_PROFILES_ACTIVE=local` and open
<http://localhost:8080/swagger-ui.html> (group `assessment`). Click **Authorize** and paste your `API_AUTH_TOKEN`
to make "Try it out" work.

Full details — how to enable it elsewhere, and how the spec is kept honest — live in
**[`server/docs/assessment.md` → API documentation](../../server/docs/assessment.md#api-documentation)**.

## Environment variables

Client lives in `client/`; Vite loads `client/.env.local` with priority over `client/.env`.

| Variable | Purpose |
|---|---|
| `VITE_API_BASE_URL` | ExamLense API base: the hestia server under its prefix (default `http://localhost:8080/examlense`). |
| `VITE_API_AUTH_TOKEN` | Static bearer token; must match the server's `API_AUTH_TOKEN` (default `dev-local-token`). |

Server secrets (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `API_AUTH_TOKEN`, optional `FILES_SIGNING_SECRET`) live in `server/.env` (gitignored). The client `.env` holds only the non-secret dev defaults above.

## Commands

From `client/`:

```bash
npm run dev          # Vite dev server at http://localhost:5173
npm run build        # production build → dist/
npm run build:dev    # development build with source maps
npm run lint         # ESLint
npm run test         # Vitest (headless)
npm run test:watch   # Vitest watch mode
npm run preview      # preview production build
```

From the repository root (the server is the project `:server` of the root Gradle build, shared by all modules):

```bash
./gradlew :server:bootRun    # start the server on :8080 (source server/.env first)
./gradlew :server:test --tests 'de.tum.cit.hestia.assessment.*'   # this module's tests — requires a running Docker daemon
./gradlew :server:bootJar    # build the executable jar
```

`./gradlew build` builds and tests the whole server.

## Testing

`./gradlew :server:test` (from the repository root) is the same gate CI runs, and a red run blocks every deploy.

The server test classes that extend `AbstractIntegrationTest` use a real `postgres:16`
container through Testcontainers (`TestDatabase`), so **Docker must be running**. The rest are
plain unit tests. You cannot filter by name reliably —
three of the Docker-backed classes end in `Test` rather than `IT` (`SmokeContextTest`,
`SecurityRulesSmokeTest`, `HealthSmokeTest`).

`check` is equivalent to `test`; the server has no linter configured.
Client tests run under Vitest + jsdom with `npm run test`.

## Key features

- **PDF parsing** → structured sections, tasks (single/multiple choice, text), context blocks, and figures.
- **From-scratch authoring** with a drag-and-drop editor (dnd-kit).
- **Solver model** selected at exam creation and locked for the run.
- **AI grading** with manual override; auto-grading for choice questions.
- **Learning Goals** — derived automatically per task by the **LearningGoalHub** functionality of the materials module: link an LGH course at exam creation, and confirming a section passes its tasks to the goal derivation of the materials module. Goals show read-only in grading and roll up per goal (with Bloom/SOLO) on the results screen. Server integration in `server/src/main/java/de/tum/cit/hestia/assessment/lgh/` (in-process ports to the materials module).
- **Results dashboard** — overall score, learning goals, per-question-type and per-task breakdowns (Recharts).

## Architecture & conventions

See [`AGENTS.md`](AGENTS.md) for the architecture overview, data-layer (React Query hooks) conventions, domain types, route map, and a file-pointer index for common UI areas. (`CLAUDE.md` just imports it, so Claude Code picks up the same file.)

## Deployment

Official deployment is on the chair **Hestia VMs** via GitHub Actions → GHCR → the shared
Traefik proxy. Three containers (`postgres`, `server`, `web`) are described in
[`compose.prod.yaml`](compose.prod.yaml) and built by
[`.github/workflows/examlense-cicd.yml`](../../.github/workflows/examlense-cicd.yml). The app is
served at `https://<APP_HOST>/examlense/`. See **[`DEPLOY.md`](DEPLOY.md)** for the full runbook.

`docker-compose.yml` remains for **local dev only** (Postgres on host port 5433).

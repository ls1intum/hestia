# ExamLense

React 18 + TypeScript SPA for **exam authoring, AI-powered parsing, solving, grading, and results analysis**. Authors upload an exam PDF (or build one from scratch), an LLM parses it into structured tasks, solves them, the author grades the AI's answers, and a results dashboard breaks down performance — including per **Learning Goal**.

## Stack

- **Client** — Vite (SWC), React 18, TypeScript, Tailwind (HESTIA design system), shadcn/ui (Radix), React Query, React Router v6, Recharts. English-only; there is no i18n layer.
- **Spring Boot server** (`server/`, JDK 21) — the only service. Owns all CRUD + ownership, parse/solve/metrics endpoints, file storage (local filesystem + HMAC-signed URLs), and SSE realtime. Auth is a static bearer token (single-user). See [`server/README.md`](server/README.md).
- **PostgreSQL** — plain Postgres (Docker, host port 5433); schema managed by Flyway in `server/src/main/resources/db/migration/`.
- **AI providers** — GWDG OpenAI-compatible models via `AI_API_KEY` + `AI_BASE_URL`, plus optional native OpenAI, Anthropic, and Gemini keys for GPT / Claude / Gemini strategies. The model catalog lives in `client/src/lib/exam/llm-models.ts`.

> Supabase (auth, Postgres+RLS, storage, realtime, edge functions) has been fully removed.

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 21 | the Spring Boot server |
| Node | 22 | the Vite client |
| Docker | any recent | local Postgres **and** the server test suite (Testcontainers) |
| Gradle | 8.10 | bundled as `server/gradlew` — do not install it separately |

Ports in use: **8081** server, **8080** Vite dev server, **5433** Postgres (5432 is often taken by another local Postgres).

## Running locally

The full local stack is **three processes**:

### 1. PostgreSQL (Docker)

```bash
docker compose up -d postgres   # Postgres 16 on host port 5433; data in a named volume
```

Flyway applies the schema automatically when the server boots.

### 2. Server

```bash
cd server
set -a; source .env; set +a   # Spring Boot does not auto-read .env
./gradlew bootRun             # http://localhost:8081
```

See [`server/README.md`](server/README.md) for configuration and a smoke test.

### 3. Client

```bash
cd client
npm install                   # .npmrc already sets legacy-peer-deps
npm run dev                   # http://localhost:8080
```

### LearningGoalHub is optional

Learning goals are derived by the separate **LearningGoalHub** service, and the deployed
instance is reachable only on the LRZ VPN. Linking an LGH course when you create an exam is
optional, and nothing breaks without it: the course picker shows "LearningGoalHub is
unreachable — you can continue without a course", section confirmation still succeeds, and
`/api/lgh/courses` returns 502 while the rest of the app works normally. You only lose the
goal insights in the grading and results views. Point `LGH_BASE_URL` at a local LGH if you
are running one.

## Environment variables

Client lives in `client/`; Vite loads `client/.env.local` with priority over `client/.env`.

| Variable | Purpose |
|---|---|
| `VITE_API_BASE_URL` | Spring Boot server base URL (default `http://localhost:8081`). |
| `VITE_API_AUTH_TOKEN` | Static bearer token; must match the server's `API_AUTH_TOKEN` (default `dev-local-token`). |

Server secrets (`AI_API_KEY`, `AI_BASE_URL`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `API_AUTH_TOKEN`, optional `FILES_SIGNING_SECRET`) live in `server/.env` (gitignored). The client `.env` holds only the non-secret dev defaults above.

## Commands

From `client/`:

```bash
npm run dev          # Vite dev server at http://localhost:8080
npm run build        # production build → dist/
npm run build:dev    # development build with source maps
npm run lint         # ESLint
npm run test         # Vitest (headless)
npm run test:watch   # Vitest watch mode
npm run preview      # preview production build
```

From `server/`:

```bash
./gradlew bootRun    # start the server on :8081 (source .env first)
./gradlew test       # full test suite — requires a running Docker daemon
./gradlew bootJar    # build the executable jar
```

## Testing

`./gradlew test` is the same gate CI runs, and a red run blocks every deploy.

Eight of the server test classes extend `AbstractIntegrationTest`, which starts a real
`postgres:16` container through Testcontainers, so **Docker must be running**. The rest are
plain unit tests. There is no unit-only Gradle task, and you cannot filter by name reliably —
three of the Docker-backed classes end in `Test` rather than `IT` (`SmokeContextTest`,
`SecurityRulesSmokeTest`, `HealthSmokeTest`).

`./gradlew check` is equivalent to `./gradlew test`; the server has no linter configured.
Client tests run under Vitest + jsdom with `npm run test`.

## Key features

- **PDF parsing** → structured sections, tasks (single/multiple choice, text), context blocks, and figures.
- **From-scratch authoring** with a drag-and-drop editor (dnd-kit).
- **Solver model** selected at exam creation and locked for the run.
- **AI grading** with manual override; auto-grading for choice questions.
- **Learning Goals** — derived automatically per task by the **LearningGoalHub** service: link an LGH course at exam creation, and confirming a section sends its tasks to LGH's goal-derivation endpoint. Goals show read-only in grading and roll up per goal (with Bloom/SOLO) on the results screen. Server integration in `server/src/main/java/app/lgh/` (`LGH_BASE_URL` env).
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

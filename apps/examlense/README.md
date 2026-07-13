# ExamLense

React 18 + TypeScript SPA for **exam authoring, AI-powered parsing, solving, grading, and results analysis**. Authors upload an exam PDF (or build one from scratch), an LLM parses it into structured tasks, solves them, the author grades the AI's answers, and a results dashboard breaks down performance — including per **Learning Goal**.

## Stack

- **Frontend** — Vite (SWC), React 18, TypeScript, Tailwind (HESTIA design system), shadcn/ui (Radix), React Query, React Router v6, i18next (en/de), Recharts.
- **Spring Boot backend** (`backend/`, JDK 21) — the only backend. Owns all CRUD + ownership, parse/solve/admin endpoints, file storage (local filesystem + HMAC-signed URLs), and SSE realtime. Auth is a static bearer token (single-user). See [`backend/README.md`](backend/README.md).
- **PostgreSQL** — plain Postgres (Docker, host port 5433); schema managed by Flyway in `backend/src/main/resources/db/migration/`.
- **AI providers** — GWDG OpenAI-compatible models via `AI_API_KEY` + `AI_BASE_URL`, plus optional native OpenAI, Anthropic, and Gemini keys for GPT / Claude / Gemini strategies. The model catalog lives in `src/lib/llm-models.ts`.

> Supabase has been fully removed (auth, Postgres+RLS, storage, realtime, edge functions). See [`docs/supabase-migration.md`](docs/supabase-migration.md) for the migration record.

## Running locally

The full local stack is **three processes**:

### 1. PostgreSQL (Docker)

```bash
docker compose up -d postgres   # Postgres 16 on host port 5433; data in a named volume
```

Flyway applies the schema automatically when the backend boots.

### 2. Backend

```bash
cd backend
set -a; source .env; set +a   # Spring Boot does not auto-read .env
./gradlew bootRun             # http://localhost:8081
```

See [`backend/README.md`](backend/README.md) for configuration and a smoke test.

### 3. Frontend

```bash
cd frontend
npm install --legacy-peer-deps
npm run dev                   # http://localhost:8080
```

## Environment variables

Frontend lives in `frontend/`; Vite loads `frontend/.env.local` with priority over `frontend/.env`.

| Variable | Purpose |
|---|---|
| `VITE_API_BASE_URL` | Spring Boot backend base URL (default `http://localhost:8081`). |
| `VITE_API_AUTH_TOKEN` | Static bearer token; must match the backend's `API_AUTH_TOKEN` (default `dev-local-token`). |

Backend secrets (`AI_API_KEY`, `AI_BASE_URL`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `API_AUTH_TOKEN`, optional `FILES_SIGNING_SECRET`) live in `backend/.env` (gitignored). The frontend `.env` holds only the non-secret dev defaults above.

## Commands

Run from `frontend/`:

```bash
npm run dev          # Vite dev server at http://localhost:8080
npm run build        # production build → dist/
npm run build:dev    # development build with source maps
npm run lint         # ESLint
npm run test         # Vitest (headless)
npm run test:watch   # Vitest watch mode
npm run preview      # preview production build
```

## Key features

- **PDF parsing** → structured sections, tasks (single/multiple choice, text), context blocks, and figures.
- **From-scratch authoring** with a drag-and-drop editor (dnd-kit).
- **Solver model** selected at exam creation and locked for the run.
- **AI grading** with manual override; auto-grading for choice questions.
- **Learning Goals** — derived automatically per task by the **LearningGoalHub** service: link an LGH course at exam creation, and confirming a section sends its tasks to LGH's goal-derivation endpoint. Goals show read-only in grading and roll up per goal (with Bloom/SOLO) on the results screen. Backend integration in `backend/src/main/java/app/lgh/` (`LGH_BASE_URL` env).
- **Results dashboard** — overall score, learning goals, per-question-type and per-task breakdowns (Recharts).

## Architecture & conventions

See [`CLAUDE.md`](CLAUDE.md) for the architecture overview, data-layer (React Query hooks) conventions, domain types, route map, and a file-pointer index for common UI areas.

## Deployment

Official deployment is on the chair **Hestia VMs** via GitHub Actions → GHCR → the shared
Traefik proxy. Three containers (`postgres`, `server`, `web`) are described in
[`compose.prod.yaml`](compose.prod.yaml) and built by
[`.github/workflows/examlense-cicd.yml`](../../.github/workflows/examlense-cicd.yml). The app is
served at `https://<APP_HOST>/examlense/`. See **[`DEPLOY.md`](DEPLOY.md)** for the full runbook.

`docker-compose.yml` remains for **local dev only** (Postgres on host port 5433).

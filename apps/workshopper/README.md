# Workshopper — AI Workshop Session Planner

Workshopper helps lecturers plan interactive teaching sessions. From a session's settings and learning goals it generates a timetable built on a learning-cycle structure, lets the lecturer edit it, generates slides per block, and exports the session as PDF and PPTX. Sessions can be grouped into courses that share settings and a slide template.

Login is TUM SAML 2.0 on every API endpoint (see [docs/AUTH.md](docs/AUTH.md)). The `local` Spring profile signs every request in as one dev user; it is used for local development and on the VPN-only `hestia-test` VM, and must never be active in production.

## Stack

| Layer    | Tech                                                         |
|----------|--------------------------------------------------------------|
| Client   | Vite + React 18 + TypeScript + Tailwind CSS                  |
| UI       | shadcn/ui (Radix UI primitives), dnd-kit                     |
| Server   | Spring Boot 3.5 (Java 21)                                    |
| Database | PostgreSQL 16, Flyway migrations                             |
| LLM      | Spring AI via `libs:shared-llm` (TUM AET Logos gateway)      |
| Export   | Apache POI (PPTX), Apache PDFBox (PDF)                       |

---

## Quick Start (Local Development)

### 1. Start the database

From `apps/workshopper`, start the local PostgreSQL container. It listens on **localhost:5433** with user, password and database `workshopper` (see `compose.yaml`):

```bash
docker compose up -d
```

### 2. Start the server

From the repository root. The `local` profile has to be chosen explicitly; it skips the SAML login:

```bash
SPRING_PROFILES_ACTIVE=local WORKSHOPPER_LOGOS_API_KEY="your-api-key-here" ./gradlew :apps:workshopper:server:bootRun
```

The server starts on http://localhost:8081 and runs the Flyway migrations on startup. Spring does not read `.env` files; see `.env.example` for all variables.

### 3. Start the client

```bash
cd apps/workshopper/client
npm install
npm run dev
```

The app opens at **http://localhost:5173/workshopper/**. The dev server proxies `/workshopper/api` to the server (`VITE_API_TARGET`, default `http://localhost:8081`). Importing goals from the Learning Goal Hub needs that app running on `VITE_LGH_TARGET` (default `http://localhost:8080`).

### Tests

```bash
./gradlew :apps:workshopper:server:test
```

Run from the repository root (this is what CI runs). The repository test starts a PostgreSQL container with Testcontainers, so Docker must be running.

```bash
cd apps/workshopper/client && npm run build
```

Type-checks and builds the client. For a manual check, run the app as above, click through the flow you changed, and reload the page halfway through to see that it resumes correctly. [docs/EXTENDING.md](docs/EXTENDING.md) lists what to check for each kind of change.

---

## App Flow

The dashboard lists courses and standalone sessions; sessions can be dragged into, out of and within courses.

**Session wizard**
1. **Setup**: title, duration, participants, session type, student background
2. **Activities**: the activity types the lecturer wants to use
3. **Materials**: the materials available in the room
4. **Review**: only for a session inside a course; shows the settings and slide template inherited from the course
5. **Goals**: enter learning goals or import them from the Learning Goal Hub; each goal can be checked by AI (refine or split), and grammar is fixed on Continue. Goal changes that are not applied can be kept as a draft.
6. **Timetable**: the AI-generated session plan. Activities follow each goal's Bloom's/SOLO level within the selected activities. Blocks can be reordered, added, deleted and edited; edits in a block's edit mode are a draft until saved with ✓.
7. **Preparation**: slide generation per block, slide editing, the PowerPoint template, and slide download
8. **Final Review**: overview and PDF/PPTX export

**Course wizard**: Setup, Activities, Materials, Template. A course's slide template is used by all of its sessions, unless a session uploads its own or removes it.

Leaving a step with unsaved input (timetable block drafts, slide edits, goal changes, settings) asks to save, keep, discard or stay.

---

## Project Structure

```
workshopper/
├── client/                ← Vite React app
│   └── src/
│       ├── components/    ← wizard steps, dashboard, timetable/ rows and panels, ui/ (shadcn)
│       ├── hooks/         ← useWizardState (all wizard state and navigation), use-toast
│       └── lib/           ← api.ts, workshop-generator.ts (types, default skeleton),
│                            activity-taxonomy.ts, block-templates.ts, constants.ts, …
├── server/                ← Spring Boot app
│   └── src/main/
│       ├── java/com/workshopper/
│       │   ├── controller/  ← REST endpoints (WorkshopController, CourseController)
│       │   ├── facade/      ← WorkshopSessionFacade: ownership checks, generation orchestration
│       │   ├── usecase/     ← generation and PPTX building (see docs/SERVER.md)
│       │   ├── service/     ← LlmService, WorkshopService (persistence, templates), PdfExportService
│       │   ├── config/      ← SecurityConfig, DevAuthFilter, AuthContext
│       │   ├── dto/  model/  repository/  exception/
│       └── resources/       ← application*.yml, prompts/*.st, templates/, db/migration (V1–V8)
├── docs/                  ← architecture, API, auth, AI integration, client and server notes
├── compose.yaml           ← local PostgreSQL
└── compose.prod.yaml      ← deployment (server, web, PostgreSQL)
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): layers, security, client state
- [docs/API.md](docs/API.md): REST endpoints
- [docs/AUTH.md](docs/AUTH.md): SAML login and the local bypass
- [docs/AI-INTEGRATION.md](docs/AI-INTEGRATION.md): LLM calls and prompts
- [docs/SERVER.md](docs/SERVER.md): server packages and database schema
- [docs/CLIENT.md](docs/CLIENT.md): client structure and wizard
- [docs/ACTIVITIES.md](docs/ACTIVITIES.md): activity catalogue and reference slide designs
- [docs/EXTENDING.md](docs/EXTENDING.md): checklists for adding steps, block phases, activities, prompts, fields and endpoints

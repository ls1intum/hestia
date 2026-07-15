# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Agent Workflow Rule

- Always produce a detailed implementation plan first. Use Plan Mode (EnterPlanMode) for this.
- Exiting Plan Mode (ExitPlanMode) counts as explicit user confirmation to proceed with implementation — no additional approval is needed.
- Do not start coding or editing files until the plan is approved (i.e., Plan Mode is exited by the user).
- Never overwrite or revert user manual changes (current or future) unless the user explicitly asks for that exact change.
- If user manual changes conflict with a requested implementation, preserve the user changes and ask for guidance instead of replacing them.

## UI / Design System Rule

- Primary design-system components live in `src/components/ui`.
- Prefer importing and composing components from `src/components/ui/*`.
- Before creating new UI from scratch, check the design-system components first and reuse them whenever appropriate.
- Minimize custom one-off UI implementations unless there is a clear missing component or product-specific need.
- If no fitting design-system component exists, ask the user for approval before adding a new UI element/component.
- For page/feature sections, prefer standardized Card-based sections over freestyle bordered boxes.
- Use `Card` directly from `src/components/ui/card` for section containers in `web-app`.

## Code Organization and Consistency Rule

- Follow the existing repository structure, naming patterns, coding style, and architectural conventions for consistency.
- Keep all relevant files colocated within the appropriate feature/domain folder instead of scattering related logic.
- Prefer small, reusable components/modules and smaller focused files over large monolithic implementations.
- Before introducing new helpers, constants, or UI elements, search for existing equivalents and reuse them when possible. Avoid copy-paste duplication.


## Commands

Frontend commands run from `frontend/`; backend from `backend/`.

```bash
npm run dev          # Start Vite dev server at http://localhost:8080
npm run build        # Production build → dist/
npm run build:dev    # Development build with source maps
npm run lint         # ESLint check
npm run test         # Run Vitest once (headless)
npm run test:watch   # Vitest in watch mode
npm run preview      # Preview production build locally
```

## Architecture

React 18 + TypeScript SPA for exam authoring, AI-powered parsing/solving/grading, and results analysis. Built with Vite (SWC), Tailwind CSS, shadcn/ui (Radix primitives).

The backend is a single **Spring Boot service** (`backend/`, JDK 21) talking to a plain **PostgreSQL** database (Docker, host port 5433). It owns everything: CRUD + ownership checks, the `parse-exam-pdf` / `solve-*` / admin endpoints, file storage (local filesystem behind `StorageService`, served via HMAC-signed time-limited URLs), and realtime via **SSE** (`/api/exams/{id}/events`, `/api/exams/events`). Auth is a **static bearer token** (`app.auth.token`, env `API_AUTH_TOKEN`) plus a per-IP rate limiter — single-user for now; `owner_id`/`user_id`/`graded_by` and storage paths are stamped server-side from the authenticated principal. (Supabase — auth, Postgres+RLS, storage, realtime, edge functions — has been fully removed; see `docs/supabase-migration.md`.) `src/lib/api/api-client.ts` is the single typed transport and `src/lib/api/sse.ts` is the SSE client. AI calls use provider-pinned strategies for GWDG/OpenAI-compatible, OpenAI, Anthropic, and Gemini models; the client-side catalog mirror lives in `src/lib/exam/llm-models.ts`. The solver model is chosen at exam creation and locked for the run.

### Data Layer

All server state flows through **React Query** hooks in `src/hooks/data/` (the `use-*` hooks below). Each hook exports query keys for cache invalidation. No Redux or global state library — UI state lives in component `useState`. There is no client-side auth hook; the backend is gated by a static bearer token (see `src/lib/api/api-client.ts`) and treats every request as the single seeded user.

Hooks and lib are grouped by concern: `src/hooks/{data,ui}/` (server-state/realtime vs generic UI hooks) and `src/lib/{api,exam,grading,learning-goals,parsing,utils}/`.

- `use-exam.ts` — `useExam(id)`, `useTasks(id)` queries
- `use-sections.ts` — `useSections(id)`, `useSectionBlocks(id)`, `useSectionFigures(id)`
- `use-task-answers.ts` — AI-generated answers per task
- `use-task-grades.ts` — grades with `useUpsertTaskGrade` mutation
- `use-exam-progress.ts` — evaluation progress via the exam SSE `progress` event
- `use-learning-goals.ts` — `useExamLearningGoals(examId)` resolved goals via the backend LGH proxy, `useLghCourses()` course picker data, `goalsByIds(all, ids)` resolver

**Realtime** is SSE: `src/lib/api/sse.ts` (`subscribeExam`, `subscribeExamsList`) opens an `EventSource` (token via `?token=` query param) and invalidates React Query caches on each event. Domain types are hand-defined in `src/lib/exam/exam-helpers.ts` / `src/lib/grading/grading.ts` (the old auto-generated Supabase DB types are gone).

### Core Domain Types

Defined in `src/lib/exam/exam-helpers.ts`:

- **Exam** — status lifecycle: `parsing → draft → ready → evaluating → grading → finished` (or `failed`)
- **Task** — types: `single_choice`, `multiple_choice`, `text`; belongs to an exam and optionally a section
- **Section** — groups tasks with context blocks and figure blocks, ordered by position
- **BlockItem** — union type produced by `mergeSectionItems()` to interleave tasks, context blocks, and figure blocks by position for rendering

Grading logic in `src/lib/grading/grading.ts`: `autoGradeChoiceTask()` scores MC questions; `effectiveScore()` merges auto/manual grades.

### Learning Goals

Derived automatically per task by the external **LearningGoalHub** (LGH) service (`apps/learninggoalhub`, VPN-only; base URL via backend env `LGH_BASE_URL`). Flow: (1) at exam creation the user links an LGH course in `StartExamDialog` (`exams.lgh_course_id`, optional — skipping it disables goal insights); (2) when a section is confirmed, the backend (`app/lgh/TaskGoalGenerationService`, `lghExecutor` pool, CAS lock on `sections.goals_started_at`) posts the section's context blocks + tasks to LGH's `POST /api/courses/{id}/exam-tasks/learning-goals` and stores the returned goal ids on `tasks.learning_goal_ids` (jsonb, ids only); re-confirm deletes the old LGH goals first (LGH doesn't dedup), unconfirm clears + best-effort deletes; (3) goals render read-only in the grading view (`ReadOnlyTaskCard`) and roll up per goal on the results "Learning Goals" tab (`LearningGoalsCard`).

Goal text/Bloom/SOLO is resolved at render through the backend proxy (`GET /api/exams/{id}/learning-goals`, `GET /api/lgh/courses` — `app/lgh/LghController`); when LGH is unreachable the UI degrades to "Goal #id" placeholders (metrics still work, ids live on our tasks). Frontend types in `src/lib/learning-goals/learning-goals.ts`; the SSE `tasks` event signals goal generation finished.

### Page Routes (React Router v6)

- `/` — redirects to `/exams`
- `/exams` — exam list/management
- `/exams/:id/edit` — drag-and-drop exam editor (dnd-kit for reordering)
- `/exams/:id/grade` — AI answer grading interface
- `/exams/:id/results` — results dashboard (Recharts)
- `/admin` — internal review dashboard

### Component Organization

Page-centric colocation: **`src/components/` holds only shared, reusable code; every page owns its private components under `src/pages/<page>/components/`.** The rule is self-explaining — a component in `components/` is shared across views; one under `pages/X/` is private to page X. Reusing a page-private component elsewhere means deliberately promoting it into `components/shared/`.

- `src/components/ui/` — shadcn/ui primitives (do not edit manually; managed by shadcn CLI)
- `src/components/shared/` — cross-page building blocks: `exam-content/` (section/block renderers shared by edit, grading, and results — incl. `read-only/` variants and `BlockHeader`), `chrome/` (sticky header/footer shell + `HelpDialog`/`IntroStepGuide`), and shared atoms (`ModelLogo`, `ThemeToggle`)
- `src/pages/<page>/<Page>.tsx` — the route entry; `src/pages/<page>/components/` — that page's private components. Pages: `exams/` (+ `start-exam/` creation wizard), `exam-edit/`, `exam-grading/` (`GradingView` + `GradeRoute`), `exam-results/`, `admin/`

### Styling

HESTIA design system built on Tailwind with custom HSL color tokens, defined in `src/index.css` and `tailwind.config.ts`. Fonts: Playfair Display (headings), Inter (body). Dark mode via `next-themes`.

### Copy / strings

The app is **English-only** — there is no i18n layer. UI strings are hardcoded directly in components. Shared enum labels live in `src/lib/exam/labels.ts` (task types, Bloom/SOLO levels, grade sources); reuse these instead of re-inlining. Note: the per-exam **content** `language` field (`en`/`de`/`other`, set in `StartExamDialog`) is unrelated to UI copy — it only tells the backend solver which language to answer that exam's PDF in.

## Common Prompt Phrases & File Pointers

When the user uses any of these phrases, treat them as references to the listed file(s) / directories. Phrases are case-insensitive and may appear in singular/plural form.

> Path convention: pointers below written as `src/...` live under **`frontend/`** (i.e. `frontend/src/...`); paths prefixed `backend/` are under the backend module.

### Top-level views / "modes"

- **"Dashboard"**, **"Exam List"**, **"Your Exams"**, **"My Exams"**, **"Exams Page"** → `src/pages/exams/Exams.tsx` + `src/pages/exams/components/`
- **"Start Exam"**, **"Upload PDF"**, **"Create From Scratch"**, **"Parse Exam"** → `src/pages/exams/start-exam/StartExamDialog.tsx` (the creation wizard); PDF pipeline in `backend/src/main/java/app/parse/` (`ParseExamService`)
- **"Edit View"**, **"Edit Mode"**, **"Editor"**, **"Exam Editor"**, **"Authoring View"** → `src/pages/exam-edit/ExamEdit.tsx` + `src/pages/exam-edit/components/` (shared section/block renderers live in `src/components/shared/exam-content/` and shell chrome in `src/components/shared/chrome/`)
- **"Grading View"**, **"Grading Mode"**, **"Grade Mode"**, **"Grade Page"** → `src/pages/exam-grading/GradingView.tsx` (routed via `src/pages/exam-grading/GradeRoute.tsx`) + `src/pages/exam-grading/components/` and the shared read-only renderers in `src/components/shared/exam-content/read-only/`
- **"Final Overview"**, **"Final Screen"**, **"Scoring Overview"**, **"Results"**, **"Results Dashboard"**, **"Insights"** → `src/pages/exam-results/ExamResults.tsx` + `src/pages/exam-results/components/`
- **"Admin"**, **"Admin Dashboard"**, **"Feedback Page"** → `src/pages/admin/AdminDashboard.tsx` (route `/admin`). A tabbed dashboard that aggregates internal review data: parsing-quality survey responses and parser performance metrics. Panels live in `src/pages/admin/components/`.
- **"Evaluating View"**, **"Evaluating Screen"** (the "solving in progress" splash) → `src/pages/exam-edit/components/EvaluatingView.tsx`
- **"Parsing View"**, **"Parsing Screen"** (the "reading your exam" splash) → `src/components/shared/exam-content/EditorLoadingView.tsx`
- **"Intro"**, **"Intro Slide"** (first-time editor intro) → `src/pages/exam-edit/components/IntroSlide.tsx`

### Shared section / block UI (used by Edit and Grading)

- **"Section Layout"**, **"Section Card"** (legacy name — now flat) → `src/components/shared/exam-content/SectionLayout.tsx`
- **"Section Title"**, **"Section Name"** (editable) → `src/pages/exam-edit/components/SectionTitleInput.tsx`
- **"Section Tabs"**, **"Tabs Bar"** (top tabs above the section) → `src/pages/exam-edit/components/SectionTabs.tsx`
- **"Carousel"**, **"Slide"** (single-section container) → `src/components/shared/exam-content/SectionCarousel.tsx`
- **"Confirm Button"**, **"Confirm Section"**, **"Section Status Chip"** → `src/pages/exam-edit/components/ConfirmSectionButton.tsx`
- **"Block Row"**, **"Task Row"**, **"Collapsed Row"** → `src/pages/exam-edit/components/BlockRow.tsx` (uses `BlockHeader` from `src/components/shared/exam-content/` for the row layout)
- **"Add Task"**, **"+ Add"**, **"Add Block"** (inline popover) → `src/pages/exam-edit/components/AddTaskInline.tsx`
- **"Chrome Header"**, **"Top Bar"**, **"Sticky Header"** → `src/components/shared/chrome/ChromeHeader.tsx`
- **"Chrome Footer"**, **"Bottom Bar"**, **"Status Bar"** → `src/components/shared/chrome/ChromeFooter.tsx`
- **"Utility Cluster"**, **"Header Actions"** (right-side controls) → `src/components/shared/chrome/ChromeUtilityCluster.tsx`
- **"Save Status"**, **"Saving Indicator"** → `src/pages/exam-edit/components/SaveStatus.tsx`

### Edit-only components

- **"Task Card"** (expanded editor) → `src/pages/exam-edit/components/TaskCard.tsx`
- **"Context Block"** (editable) → `src/pages/exam-edit/components/ContextBlockCard.tsx`
- **"Figure Block"** (editable) → `src/pages/exam-edit/components/FigureBlockCard.tsx`
- **"Item Collapse State"**, **"Expand/Collapse Memory"** → `src/hooks/ui/use-item-collapse-state.ts`
- **"Section Confirmations"** (pre-solve + goal-generation trigger) → `src/hooks/data/use-section-confirmations.ts`

### Grading-only components

- **"Read-Only Question"**, **"Question Block"** (grading: the static, card-less question — prompt + learning goals + optional reference answer) → `src/pages/exam-grading/components/ReadOnlyQuestionBlock.tsx`
- **"Read-Only Task"**, **"Task Preview"** (the single question+panel card — shared; used by the **results** view `AllTasksList`; grading composes `ReadOnlyQuestionBlock` + `TaskGradingPanel` instead) → `src/components/shared/exam-content/read-only/ReadOnlyTaskCard.tsx`
- **"Read-Only Context"** → `src/components/shared/exam-content/read-only/ReadOnlyContextBlock.tsx`
- **"Read-Only Figure"** → `src/components/shared/exam-content/read-only/ReadOnlyFigureBlock.tsx`
- **"Grading Panel"**, **"Score Panel"**, **"Manual Grade"** (grading: the gradable AI-answer + score, rendered as the `primary` card) → `src/pages/exam-grading/components/TaskGradingPanel.tsx`

### Results dashboard cards

- **"Overall Score"** → `src/pages/exam-results/components/OverallScoreCard.tsx`
- **"By Question Type"** → `src/pages/exam-results/components/ByQuestionTypeCard.tsx`
- **"Task Breakdown"**, **"Per-Task Table"** → `src/pages/exam-results/components/TaskBreakdownTable.tsx`
- **"Task Score Chart"** → `src/pages/exam-results/components/TaskScoreBarChart.tsx`
- **"Learning Goals"** → `src/pages/exam-results/components/LearningGoalsCard.tsx`
- **"Figures Comparison"** → `src/pages/exam-results/components/FiguresComparisonCard.tsx`

### Domain / data

- **"Exam Type"**, **"Task Type"**, **"Section Type"**, **"Block Item"** → `src/lib/exam/exam-helpers.ts`
- **"Auto Grade"**, **"Effective Score"**, **"Grading Logic"** → `src/lib/grading/grading.ts`
- **"Solver Model"**, **"AI Model"**, **"LLM Models"** → `src/lib/exam/llm-models.ts`
- **"Learning Goals"**, **"Goals API"**, **"LGH"**, **"LearningGoalHub"**, **"Bloom"**, **"SOLO"** → `src/lib/learning-goals/learning-goals.ts` (types), `src/hooks/data/use-learning-goals.ts` (hooks); backend integration in `backend/src/main/java/app/lgh/`
- **"Backend Client"**, **"Spring Boot API"**, **"API Client"** → `src/lib/api/api-client.ts`
- **"Realtime"**, **"SSE"**, **"Progress Channel"** → `src/lib/api/sse.ts`, `src/hooks/data/use-exam-progress.ts`

### Styling / system

- **"Design Tokens"**, **"HESTIA Tokens"**, **"Theme"** → `src/index.css`, `tailwind.config.ts`
- **"Strings"**, **"Copy"**, **"Labels"** → strings are inlined in components (English-only, no i18n); shared enum labels in `src/lib/exam/labels.ts`
- **"shadcn"**, **"UI Primitives"**, **"Design System Components"** → `src/components/ui/`

When the requested concept is ambiguous (e.g. "task card" could mean the editable or the read-only variant), ask which view (Edit vs Grading) the user means before editing.

## Path Alias

`@/` maps to `./frontend/src/` (configured in `frontend/vite.config.ts` and `frontend/tsconfig.app.json`).

## Environment Variables

Frontend vars use the `VITE_` prefix (in `frontend/.env`, overridable in `frontend/.env.local`):

- `VITE_API_BASE_URL` — Spring Boot backend base URL (default `http://localhost:8081`).
- `VITE_API_AUTH_TOKEN` — static bearer token; must match the backend's `API_AUTH_TOKEN` (default `dev-local-token`).

Vite loads `frontend/.env.local` with priority over `frontend/.env`. Backend secrets (`AI_API_KEY`, `AI_BASE_URL`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `API_AUTH_TOKEN`, optional `FILES_SIGNING_SECRET`) live in `backend/.env`, which **Spring Boot does not auto-load** — source it before running: `set -a; source backend/.env; set +a; ./gradlew bootRun`. `LGH_BASE_URL` points the backend at LearningGoalHub (default `http://localhost:8080` for a local LGH; deployed instance is VPN-only). A Docker `postgres` service must be up (host port 5433); see `docker-compose.yml`.

## TypeScript Config

Relaxed strictness: `noImplicitAny: false`, `strictNullChecks: false` in `tsconfig.app.json`.

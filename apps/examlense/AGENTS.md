# AGENTS.md

This file provides guidance to AI coding agents working on ExamLense. Claude Code reads it via the one-line `CLAUDE.md` import, so keep this file the single source — do not fork a second copy.

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

## Code Comments Rule

- Include only necessary code comments. Follow the "comment the *why*, not the *what*" principle.
- Do not write comments that duplicate what the code already says; the code is the source of truth for *what* it does.
- Add a comment only when it earns its place — to explain unidiomatic or surprising code (why this approach, a non-obvious constraint, a workaround) or to document public APIs / exported functions.
- Prefer clear names and small functions over explanatory comments; if code needs a comment to be understood, first consider whether it can be made clearer.
- Keep comments current: update or remove them when the code they describe changes, so they never drift out of sync.


## Commands

Client commands run from `client/`; the server is the project `:server` of the root Gradle build (`./gradlew :server:bootRun`, `./gradlew :server:test` from the repository root).

```bash
npm run dev          # Start Vite dev server at http://localhost:5173
npm run build        # Production build → dist/
npm run build:dev    # Development build with source maps
npm run lint         # ESLint check
npm run test         # Run Vitest once (headless)
npm run test:watch   # Vitest in watch mode
npm run preview      # Preview production build locally
```

## Architecture

React 18 + TypeScript SPA for exam authoring, AI-powered parsing/solving/grading, and results analysis. Built with Vite (SWC), Tailwind CSS, shadcn/ui (Radix primitives).

The server is the `assessment` module (package `de.tum.cit.hestia.assessment`) of the one hestia **Spring Boot application** (`server/`, JDK 21), served under the URL prefix `/examlense` (locally `http://localhost:8080/examlense`) and using the schema `assessment` of the shared **PostgreSQL** database `hestia`. It owns everything: CRUD + ownership checks, the `parse-exam-pdf` / `solve-*` / `parse-metrics` endpoints, file storage (local filesystem behind `StorageService`, served via HMAC-signed time-limited URLs), and realtime via **SSE** (`/api/exams/{id}/events`, `/api/exams/events`). `created_by`/`user_id`/`graded_by`, `org_id` and storage paths (`<org_id>/...`) are stamped server-side from the logged-in user.

**Auth is the session login of the hestia server** (core: TUM SAML, session cookie, CSRF double submit with the `XSRF-TOKEN` cookie and the `X-XSRF-TOKEN` header; the `local` profile signs in a dev user). ExamLense has no users, tokens or registration of its own: the module uses `core.CurrentUser`, `core.CourseAccess` and `core.OrgContext`. Every table extends `OrgScoped` (`org_id`, Hibernate tenant filter plus Postgres RLS policy `org_isolation`, migration `V9__assessment_org_scope.sql`). An exam follows the roles in its core course (`course_id`; read: viewer, write: editor, enforced by `assessment.shared.Access`); `created_by` is only a record of who created it. `/api/files/**` needs a login whose organisation matches the `<org_id>/` path prefix plus the HMAC signature. Async executors and the SSE hub carry the organisation (`OrgContext.taskDecorator()` / `wrap`). Cross-user surfaces (`/api/parse-metrics`) require `ROLE_ADMIN`, i.e. a platform admin (`hestia.security.platform-admins`); see [`docs/auth-and-saml-cutover.md`](docs/auth-and-saml-cutover.md). Every model call goes through the `ai` module (`LlmGateway`); its external providers (OpenAI, Anthropic, Gemini) need the user's consent (`/api/ai-consent`, HTTP 428 `CONSENT_REQUIRED`), and LLM spend is limited per user by `ai.LlmJobs` (`hestia.ai.job-limits.exam-parse` / `exam-solve`, 10 / 30 per day, HTTP 429); there is no organisation-wide ceiling. The per-IP `RateLimitFilter` stays as pre-auth flood protection. Both IP-keyed limits resolve the caller through `assessment.security.ClientIp`, which reads `X-Forwarded-For` using `app.ratelimit.trusted-proxy-hops` (default 2: Traefik, then nginx) — the leftmost entry is client-supplied and must never be trusted. (Supabase — auth, Postgres+RLS, storage, realtime, edge functions — has been fully removed.) `src/lib/api/api-client.ts` is the single typed transport and `src/lib/api/sse.ts` is the SSE client. AI calls use provider-pinned strategies for the native OpenAI, Anthropic, and Gemini APIs; the client-side catalog mirror lives in `src/lib/exam/llm-models.ts`. The solver model is chosen at exam creation and locked for the run. `SolveExaminationService` records it on an `evaluation_runs` row (one per examination, replaced on re-solve) together with the model's reasoning depth. Reasoning depth is **not** selectable: ExamLense sends no thinking parameter, so every solve runs at the provider's default, and the run stores what that default is from `SolverStrategy.defaultThinking`, in the provider's own vocabulary (`off` for Claude Opus 4.8, `medium` for GPT-5.5 and Gemini 3.5 Flash, `dynamic` for the legacy Gemini 2.5 Flash). Null means the model does not reason by default, or is a retired entry that can no longer be called.

The server API is documented by a generated **OpenAPI spec** (springdoc): `GET /v3/api-docs/assessment` (springdoc group `assessment`), Swagger UI at `/swagger-ui.html`. Both are enabled by the `local` profile and off in deployments (`app.docs.enabled` / `API_DOCS_ENABLED`). **A new or changed endpoint must carry a `@Tag` and an `@Operation(summary = ...)`** — `OpenApiDocsTest` fails the build otherwise. Spec metadata lives in `server/src/main/java/de/tum/cit/hestia/assessment/config/OpenApiConfig.java` and the shared error envelope in `server/src/main/java/de/tum/cit/hestia/assessment/error/ErrorResponse.java`.

### Data Layer

All server state flows through **React Query** hooks in `src/hooks/data/` (the `use-*` hooks below). Each hook exports query keys for cache invalidation. No Redux or global state library — UI state lives in component `useState`. Auth is the session cookie: `src/lib/api/api-client.ts` sends `credentials: "include"` and the `X-XSRF-TOKEN` header on non-GET requests, and a 401 sends the browser to `/saml2/authenticate/tum`. `src/hooks/data/use-me.ts` (`useMe()`) is the signed-in user and the session probe; `SignInGate` (`src/pages/sign-in/`) wraps the router and shows the app once `/api/me` answers. Sign-out is `POST api/logout` (204) followed by a reload.

Hooks and lib are grouped by concern: `src/hooks/{data,ui}/` (server-state/realtime vs generic UI hooks) and `src/lib/{api,exam,grading,learning-goals,parsing,utils}/`.

- `use-exam.ts` — `useExam(id)`, `useTasks(id)` queries
- `use-sections.ts` — `useSections(id)`, `useSectionBlocks(id)`, `useSectionFigures(id)`
- `use-task-answers.ts` — AI-generated answers per task
- `use-task-grades.ts` — grades with `useUpsertTaskGrade` mutation
- `use-exam-progress.ts` — evaluation progress via the exam SSE `progress` event
- `use-learning-goals.ts` — `useExamLearningGoals(examId)` resolved goals, `useCourses()` course picker data
- `use-me.ts` — `useMe()` the signed-in user + remaining LLM quota

**Realtime** is SSE: `src/lib/api/sse.ts` (`subscribeExam`, `subscribeExamsList`) opens an `EventSource` (session cookie, no credential in the URL) and invalidates React Query caches on each event. Domain types are hand-defined in `src/lib/exam/exam-helpers.ts` / `src/lib/grading/grading.ts` (the old auto-generated Supabase DB types are gone).

### Core Domain Types

Defined in `src/lib/exam/exam-helpers.ts`:

- **Examination** — status lifecycle: `parsing → draft → ready → evaluating → grading → finished` (or `failed`)
- **TaskBlock** — types: `single_choice`, `multiple_choice`, `text`; belongs to an examination and optionally a section
- **Section** — groups task blocks with context blocks and figure blocks, ordered by position
- **Block** — union type produced by `mergeSectionItems()` to interleave task, context, and figure blocks by position for rendering

Type names follow the thesis analysis object model:
`Examination`, `TaskBlock`, `AnswerOption`, `AIAnswer`, `Grade`, `Block`. The database tables,
REST paths and JSON field names still say `exam`/`task` — entities pin their table with
`@Table`, so the two vocabularies are bridged at the mapping layer rather than by a migration.

Grading logic in `src/lib/grading/grading.ts`: `autoGradeChoiceTask()` scores MC questions; `effectiveScore()` merges auto/manual grades.

### Learning Goals

Goals live in `core` (`LearningGoalApi`); a task's goals are alignment links (`AlignmentApi`, module assessment, target type `TASK`, target id = task id), read through `assessment/lgh/TaskGoalLinks`, so a goal renamed in core shows its new text without any import. Flow: (1) every exam belongs to a core course (`exams.course_id`, not null): the user picks one in `StartExamDialog`, otherwise the exam goes into the personal course ("Einzelkram", `CourseApi.personalCourse()`); `PUT /api/exams/{id}/course` moves it (editor on both courses; the links of its tasks to goals of the old course are removed, `MoveCourseDialog` confirms this); (2) when a section is confirmed, the server (`assessment/lgh/TaskBlockGoalGenerationService`, `lghExecutor` pool, CAS lock on `sections.goals_started_at`) passes the section's context blocks + tasks to `ExamGoalDerivation.derive(courseId, ...)` and links the returned goals to the tasks; re-confirm and unconfirm unlink the task and remove the goals derived for it (origin EXAM, created via assessment); deleting a task or exam removes its links; (3) goals render read-only in the grading view (`ReadOnlyTaskCard`) and roll up per goal on the results "Learning Goals" tab (`LearningGoalsCard`).

`GET /api/exams/{id}/tasks` carries `learning_goal_ids` (strings) and `learning_goals`; `GET /api/exams/{id}/learning-goals` lists the goals linked to the exam's tasks; `GET/POST /api/lgh/courses` list and create the caller's courses (UUID string ids, `personal` flag). Client types in `src/lib/learning-goals/learning-goals.ts`; the SSE `tasks` event signals goal generation finished.

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

The app is **English-only** — there is no i18n layer. UI strings are hardcoded directly in components. Shared enum labels live in `src/lib/exam/labels.ts` (task types, Bloom/SOLO levels); reuse these instead of re-inlining.

## Common Prompt Phrases & File Pointers

When the user uses any of these phrases, treat them as references to the listed file(s) / directories. Phrases are case-insensitive and may appear in singular/plural form.

> Path convention: pointers below written as `src/...` live under **`client/`** (i.e. `client/src/...`); paths prefixed `server/` are under the server module.

### Top-level views / "modes"

- **"Dashboard"**, **"Exam List"**, **"Your Exams"**, **"My Exams"**, **"Exams Page"** → `src/pages/exams/Exams.tsx` + `src/pages/exams/components/`
- **"Start Exam"**, **"Upload PDF"**, **"Create From Scratch"**, **"Parse Exam"** → `src/pages/exams/start-exam/StartExamDialog.tsx` (the creation wizard); PDF pipeline in `server/src/main/java/de/tum/cit/hestia/assessment/parse/` (`ParseExaminationService`)
- **"Edit View"**, **"Edit Mode"**, **"Editor"**, **"Exam Editor"**, **"Authoring View"** → `src/pages/exam-edit/ExamEdit.tsx` + `src/pages/exam-edit/components/` (shared section/block renderers live in `src/components/shared/exam-content/` and shell chrome in `src/components/shared/chrome/`)
- **"Grading View"**, **"Grading Mode"**, **"Grade Mode"**, **"Grade Page"** → `src/pages/exam-grading/GradingView.tsx` (routed via `src/pages/exam-grading/GradeRoute.tsx`) + `src/pages/exam-grading/components/` and the shared read-only renderers in `src/components/shared/exam-content/read-only/`
- **"Final Overview"**, **"Final Screen"**, **"Scoring Overview"**, **"Results"**, **"Results Dashboard"**, **"Insights"** → `src/pages/exam-results/ExamResults.tsx` + `src/pages/exam-results/components/`
- **"Admin"**, **"Admin Dashboard"**, **"Feedback Page"** → `src/pages/admin/AdminDashboard.tsx` (route `/admin`, gated by `AdminRoute.tsx`; admins only). Holds the parser performance metrics; shown only when `/api/me` says `is_admin`. Panels live in `src/pages/admin/components/`.
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

- **"Exam Type"**, **"Examination"**, **"Task Type"**, **"Task Block"**, **"Section Type"**, **"Block"** → `src/lib/exam/exam-helpers.ts`
- **"Auto Grade"**, **"Effective Score"**, **"Grading Logic"** → `src/lib/grading/grading.ts`
- **"Solver Model"**, **"AI Model"**, **"LLM Models"** → `src/lib/exam/llm-models.ts`
- **"Learning Goals"**, **"Goals API"**, **"Bloom"**, **"SOLO"** → `src/lib/learning-goals/learning-goals.ts` (types), `src/hooks/data/use-learning-goals.ts` (hooks); server integration in `server/src/main/java/de/tum/cit/hestia/assessment/lgh/`
- **"Server Client"**, **"Spring Boot API"**, **"API Client"** → `src/lib/api/api-client.ts`
- **"Auth"**, **"Login"**, **"Sign In"**, **"Sign-In Gate"** → `src/pages/sign-in/SignInGate.tsx`, `src/hooks/data/use-me.ts`, `src/lib/api/api-client.ts`; server side in core (`CoreSecurityConfig`, `CurrentUser`, `CourseAccess`) and `assessment/shared/Access.java`, design in `docs/auth-and-saml-cutover.md`
- **"User Menu"**, **"Account Menu"**, **"Sign Out"** → `src/components/shared/chrome/UserMenu.tsx`
- **"Quota"**, **"Rate Limit"**, **"LLM Limit"**, **"Global Ceiling"** → `server/src/main/java/de/tum/cit/hestia/ai/LlmJobs.java` (per-user job limits), `assessment/security/RateLimitFilter.java` (per-IP, pre-auth), `assessment/security/ClientIp.java` (X-Forwarded-For handling)
- **"Realtime"**, **"SSE"**, **"Progress Channel"** → `src/lib/api/sse.ts`, `src/hooks/data/use-exam-progress.ts`

### Styling / system

- **"Design Tokens"**, **"HESTIA Tokens"**, **"Theme"** → `src/index.css`, `tailwind.config.ts`
- **"Strings"**, **"Copy"**, **"Labels"** → strings are inlined in components (English-only, no i18n); shared enum labels in `src/lib/exam/labels.ts`
- **"shadcn"**, **"UI Primitives"**, **"Design System Components"** → `src/components/ui/`

When the requested concept is ambiguous (e.g. "task card" could mean the editable or the read-only variant), ask which view (Edit vs Grading) the user means before editing.

## Path Alias

`@/` maps to `./client/src/` (configured in `client/vite.config.ts` and `client/tsconfig.app.json`).

## Environment Variables

Client vars use the `VITE_` prefix (in `client/.env`, overridable in `client/.env.local`):

- `VITE_API_BASE_URL` — base URL of the ExamLense API: the hestia server under its prefix (default `http://localhost:8080/examlense`).
Vite loads `client/.env.local` with priority over `client/.env`. Server limit knobs: `API_RATELIMIT_TRUSTED_PROXY_HOPS`, `HESTIA_AI_LIMIT_EXAM_PARSE`, `HESTIA_AI_LIMIT_EXAM_SOLVE`. Server secrets (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, optional `FILES_SIGNING_SECRET`) live in `server/.env`, which **Spring Boot does not auto-load** — source it before running: `set -a; source server/.env; set +a; ./gradlew :server:bootRun` (from the repository root). Learning goals come from the `materials` module of the same server, so there are no LGH settings. A PostgreSQL with the database `hestia` and the roles from `infra/postgres/init/01-roles.sql` must be running (default `localhost:5432`).

## TypeScript Config

Relaxed strictness: `noImplicitAny: false`, `strictNullChecks: false` in `tsconfig.app.json`.

# Workshopper Architecture

This document captures the core architectural decisions and boundaries of the Workshopper application, so future developers (and AI agents) understand *why* things are built the way they are.

## 1. Server: Use Cases and a Facade
Originally, all generation logic (LLM calls, PPTX building, DB saving) lived in one large `WorkshopService.java`. That made testing hard and caused transaction timeouts during slow LLM calls. The server is now split into layers:

* **Use cases** (`usecase/`): focused classes for one business action each. They handle no HTTP and no transactions.
  * `GenerateTimetableUseCase`: one LLM call per timetable block plus the session title
  * `GenerateSlideBlockUseCase`: slides for one block
  * `AssemblePptxUseCase`: builds the PPTX from the default template
  * `RefineLearningGoalUseCase`, `FixGoalsGrammarUseCase`: goal checks
  * Helpers: `ActivityRecommender` (Bloom's/SOLO activity choice), `TemplateBranding` (applies an uploaded template's title slide, logo and footer), `PptxTextFit` (shrinks text to fit its box)
* **`WorkshopSessionFacade`**: orchestrates generation. It verifies ownership, calls the use cases outside any transaction, and then persists the result in a short transaction. It also guards against double submission: a second generation request for the same session while one is running gets `409 Conflict`.
* **Controllers** (`WorkshopController`, `CourseController`): the HTTP boundary. Generation goes through the facade; a controller never calls a use case directly. Controllers have no `try`/`catch`: `ApiExceptionHandler` turns exceptions into answers (`AccessDeniedException` → 403, `SessionLockedException` → 409, `ResponseStatusException` → its status, anything else → 500 with `{"error": ...}`, logged with the request).
* **`WorkshopService`**: persistence of sessions, courses, drafts and slide templates, including which template applies to a session. No generation logic.

## 2. Security & Environments (The Profile Gate)
Workshopper integrates with TUM Central IT via SAML 2.0. Developing locally against real SAML is painful, so there is a bypass:

* **`DevAuthFilter`**: active *only* when the `local` Spring profile is active. It signs every request in as `dev-local-user`.
* **No default profile**: `application.yml` sets no profile, so `local` must always be chosen explicitly (`SPRING_PROFILES_ACTIVE=local`).
* **The Profile Gate**: `SecurityConfig.java` refuses to start (`IllegalStateException`) if `local` is active together with `prod`, or inside Kubernetes or Fly.io (`KUBERNETES_SERVICE_HOST`, `FLY_APP_NAME`).
* **Environments**:
  * Laptop: `SPRING_PROFILES_ACTIVE=local`, SAML bypassed.
  * `hestia-test` (VM, VPN-only): `SPRING_PROFILES_ACTIVE=local` in its `.env`, SAML bypassed, because SAML access was only requested for production. Every tester is the same `dev-local-user` and sees the same sessions.
  * Production: `compose.prod.yaml` defaults to `prod`, real SAML enforced.

## 3. API Authorization & Error Handling
All `/api/**` endpoints except `GET /api/workshop/health` (and the SAML metadata endpoint) require an authenticated user. An unauthenticated request gets `401 Unauthorized`; the client (`handleAuthError` in `api.ts`) then redirects the browser to `<base>/saml2/authenticate/tum`, which starts the SAML login.

**Per-resource authorization (IDOR protection):** Spring Security checks that a user is logged in, not that they own a resource. Every endpoint that reads, changes or deletes a session by ID calls `facade.verifyOwnership(id)`; every endpoint that refers to a course (including moving a session into one, or saving a draft with a `courseId`) calls `facade.verifyCourseOwnership(id)`, which also returns `404` for unknown courses. Ownership failures throw `AccessDeniedException`, which `ApiExceptionHandler` turns into `403 Forbidden`.

## 4. Data Model
* `workshop_sessions`: one row per session, with its settings, the draft state of the wizard (`draft_state_json`), the generated timetable (`session_json`) and slides (`slides_json`), all JSONB. A session may belong to a course (`course_id`).
* `courses`: groups sessions and stores the course settings that new sessions inherit.
* `slide_templates`: uploaded PPTX files, referenced by a session or a course.

**Which slide template applies:** the session's own upload, otherwise its course's template, otherwise the Workshopper default. A session can opt out of its course's template (`use_default_template`, migration V8), which is what removing a template in the slide workstation does. Uploaded templates only brand the default design; see `TemplateBranding`.

## 5. Client State
`client/src/hooks/useWizardState.ts` holds all wizard state (plain React state, no external store): the current step and which steps are reachable, the session or course being edited, the debounced draft saves, and the leave guard. `App.tsx` is a layout wrapper that renders the current step and the step bar.

**Unsaved input:** a step with unsaved input registers a `LeaveGuard`. Every way of leaving the step (step bar, Back/Next, dashboard, browser back, reload) asks the guard first, so the user can save, keep, discard or stay.
* Timetable: a block's edit mode is a draft until saved with ✓.
* Preparation: slide edits.
* Goals: goal changes can be kept as a draft (`keptGoals`) without regenerating the timetable.
* Settings forms: Setup, Activities, Materials.

Waiting draft saves are sent immediately on navigation instead of being dropped.

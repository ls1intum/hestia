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
* **`WorkshopSessionFacade`**: orchestrates generation. It verifies the course role, calls the use cases outside any transaction, and then persists the result in a short transaction. It also guards against double submission: a second generation request for the same session while one is running gets `409 Conflict`.
* **Controllers** (`WorkshopController`, `CourseController`): the HTTP boundary. Generation goes through the facade; a controller never calls a use case directly. Controllers have no `try`/`catch`: `ApiExceptionHandler` turns exceptions into answers (`AccessDeniedException` and a missing course role → 403, an unknown course or one the user is not a member of → 404, `SessionLockedException` → 409, `ResponseStatusException` → its status, anything else → 500 with `{"error": ...}`, logged with the request).
* **`WorkshopService`**: persistence of sessions, courses, drafts and slide templates, including which template applies to a session. No generation logic.

## 2. Security & Environments (The Profile Gate)
Hestia has one SAML 2.0 login, owned by core (session cookie, CSRF header; see AUTH.md). Developing locally against real SAML is painful, so there is a bypass:

* **`DevAuthFilter`**: active *only* when the `local` Spring profile is active. It signs every request in as `dev-local-user` of the organisation `local.test`.
* **No default profile**: `application.yml` sets no profile, so `local` must always be chosen explicitly (`SPRING_PROFILES_ACTIVE=local`).
* **The Profile Gate**: core's `DeployGuard` refuses to start (`IllegalStateException`) if `local` is active together with `prod` or on the Production deploy target (`HESTIA_DEPLOY_TARGET`), or if the dev login's header impersonation (`hestia.dev-login.impersonation`, tests only) is on in any deployment. The dev login is never a platform admin. The SAML login itself is also core's.
* **Environments**:
  * Laptop: `SPRING_PROFILES_ACTIVE=local`, SAML bypassed.
  * `hestia-test` (VM, VPN-only): the deploy writes `ENVIRONMENT=Staging` into `.env`, and `application.yml` maps the `Staging` profile to `local`, so SAML is bypassed, because SAML access was only requested for production. `Production` maps to `prod`. Every tester is the same `dev-local-user` and sees the same sessions.
  * Production: `compose.prod.yaml` defaults to `prod`, real SAML enforced.

## 3. API Authorization & Error Handling
All `/workshopper/api/**` endpoints (the client's nginx maps `/api/` to it) except `GET /workshopper/api/workshop/health` require an authenticated user. An unauthenticated request gets `401 Unauthorized`; the client (`handleAuthError` in `api.ts`) then redirects the browser to `<base>/saml2/authenticate/tum`, which starts the SAML login. Writes carry the CSRF token (`X-XSRF-TOKEN`, copied from the `XSRF-TOKEN` cookie).

**Per-resource authorization (IDOR protection):** Spring Security checks that a user is logged in, not what they may touch. A teaching course hangs on a `core` course (`core_course_id`) and takes its access from the user's role there: `VIEWER` reads, `EDITOR` changes (drafts, generation, templates, renaming), `OWNER` deletes. Every endpoint that reads a session or course by ID calls `facade.verifyRead(id)`, every one that changes it `facade.verifyWrite(id)`; endpoints that refer to a course call `facade.verifyCourseRead/Write/Owner(id)`. A session without a course belongs to its creator alone (`404` for everybody else). The checks go through core's `CourseAccess`: no membership gives `404`, a role that is too small `403`. Organisation scope comes on top of that: entities extend `OrgScoped`, so another university's rows are never loaded.

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

# Workshopper: Architecture Refactor & Code Quality Instructions

**Audience:** coding agent (Claude Code or similar) working in the `hestia` monorepo, `workshopper` app.
**Goal:** take Workshopper from "functional prototype" to "final product" without breaking existing behavior.
**Chosen architecture:** Option 2 from the architecture report — Feature-Sliced Design + Zustand/React Query on the frontend; Use-Case pattern + Facade layer on the backend. Do NOT adopt XState or Hexagonal Architecture (Option 3) — out of scope, too much ceremony for this project's size and timeline. DO borrow two Option 1 items as prerequisites: externalized prompt templates and Spring AI's `StructuredOutputConverter`.

Work through the phases **in order**. Do not start refactoring (Phase 2+) before Phase 0 (safety net) exists and passes. Each phase should end with a commit and a short summary of what changed and what was verified.

---

## Phase 0 — Safety net before touching anything

Purpose: create a regression net so refactors can be verified mechanically, not just "looks right."

1. Write end-to-end / integration smoke tests for the current happy path BEFORE any refactor:
   - Backend: a Spring Boot Test (with Testcontainers + Postgres) that drives the full flow — create session → generate timetable → edit a block → generate slides → export PDF → export PPTX. Assert on response shapes/status codes, not internal implementation.
   - Frontend: one or two Playwright/Cypress (or RTL if no e2e tool is set up) tests covering: complete the 4-step wizard input, review/edit generated timetable, go through the prep step, download exports.
   - Mock the actual LLM calls in these tests (fixed fixture responses) so tests are deterministic and don't burn API credits.
2. Snapshot current DB schema (Flyway/Liquibase migration state, or a schema dump) so later authz/query changes can be diffed against a known baseline.
3. Do not proceed to Phase 2 until these tests pass against the CURRENT (pre-refactor) code.

---

## Phase 1 — Prerequisites (borrowed from Option 1)

1. **Externalize LLM prompts.** Move every `StringBuilder`/string-concatenated prompt out of `WorkshopService` (and any other service) into `.st` (StringTemplate) files under `src/main/resources/prompts/`. One file per prompt purpose (e.g. `generate-timetable.st`, `refine-goal.st`, `generate-slide-block.st`). Services should load templates by name, not build strings inline.
2. **Adopt Spring AI structured output.** Replace manual `String` → regex/substring JSON extraction → Jackson parsing with Spring AI's `StructuredOutputConverter` (or `ChatClient`'s `.entity()` mapping) wherever the LLM is expected to return structured data. Remove the manual extraction code entirely once converted — don't leave it as dead fallback code.

Run Phase 0 tests after this phase. They must still pass (behavior should be unchanged, only internals).

---

## Phase 2 — Backend restructure: Use-Case pattern + Facade

1. Break `WorkshopService` (~900 lines) into single-responsibility use cases, e.g.:
   - `GenerateTimetableUseCase`
   - `RefineLearningGoalUseCase`
   - `ExtractGoalsUseCase`
   - `GenerateSlideBlockUseCase`
   - `SaveDraftUseCase`
   Each use case: one public entry point, its own unit tests, no direct HTTP/controller concerns.
2. Introduce a `WorkshopFacade` (or one facade per bounded area if `workshopper` really has distinct sub-domains) that `WorkshopController` calls. The facade orchestrates use cases + repository access. Controllers should contain NO business logic, NO stream processing logic beyond translating to HTTP, and NO JSON deserialization beyond DTO binding.
3. Split `PptxExportService` (~100KB) into smaller collaborators as this migration happens naturally — e.g. `SlideMasterBuilder`, `ContentSlideBuilder`, `AgendaSlideBuilder` — each called from a `GenerateSlidesUseCase`. Don't do this as a separate mechanical pass; let it fall out of moving logic into use cases.
4. Repository layer: confirm every query is a Spring Data JPA derived query or a `@Query` using named/positional **bind parameters** — never string-concatenated JPQL/SQL. Flag and fix any exception during this pass (see Security section below).

Run Phase 0 tests after this phase.

---

## Phase 3 — Frontend restructure: Feature-Sliced Design + Zustand/React Query

1. Introduce **Zustand** for cross-cutting UI state (current wizard step, draft/save status, lecture-vs-session mode) and **`@tanstack/react-query`** for all server communication (timetable generation, slide generation, LearningGoalHub fetches, export calls). Remove manual `useEffect`-based fetching and manual loading/error state once migrated.
2. Reorganize `src/` by feature, not by technical layer, e.g.:
   ```
   src/
     features/
       wizard/          (session settings, activities, materials, learning goals substeps)
       timetable-review/
       prep/             (slides + todo checklist)
       export/
     shared/
       ui/               (shadcn components, generic building blocks)
       api/               (react-query hooks, API client)
       state/            (zustand stores)
   ```
3. `App.tsx` should shrink to routing/layout only — step orchestration lives in a `useWorkshopFlow` hook (or the Zustand store itself), not in the root component.
4. Eliminate prop drilling: components read from the store/react-query hooks directly rather than receiving 3-4 levels of props.

Run Phase 0 tests after this phase.

---

## Phase 4 — Resilience against misuse ("dumb user" hardening)

This is NOT just a frontend concern — assume every frontend guard can be bypassed (refresh, direct API calls, browser back/forward, double-click, multiple tabs). Backend must independently enforce correctness.

1. **State transition validation is server-side.** The backend, not just the UI, must reject invalid transitions (e.g. trying to generate slides for a session with no timetable yet, or editing a session after it's been marked exported/final if that's a real constraint). Return clear 4xx errors the frontend can surface.
2. **Idempotency on generation/submit endpoints.** Double-clicking "Generate" or refreshing mid-request must not create duplicate LLM calls, duplicate DB rows, or duplicate exports. Use an idempotency key (client-generated UUID per generation attempt) or debounce+lock server-side per session ID while a generation is in flight; return the in-flight/last result instead of starting a second one.
3. **Refresh-safe drafts.** Since users already asked for session persistence (per prior feedback), make sure draft state is saved to the backend (not just localStorage) frequently enough that a refresh mid-wizard doesn't lose meaningful progress, and that reloading the page restores the correct step.
4. **Back/forward navigation safety.** Jumping between wizard steps via browser back/forward must not desync the Zustand store from what's actually persisted server-side — on mount/step-entry, reconcile against the backend's view of the session rather than trusting only client memory.
5. **Repeated-click guards.** Disable/debounce action buttons while a request is in flight; show a clear loading state rather than allowing stacked requests.

Add targeted tests for each of these (see Phase 6).

---

## Phase 5 — Security hardening (bare-minimum but real)

1. **No direct LLM proxy exposure.** Confirm there is no endpoint that forwards an arbitrary user-supplied prompt straight to the LLM provider. All LLM calls must go through a use case that constructs the prompt server-side from validated inputs — the client should never control the raw prompt text or model parameters.
2. **Ownership/authorization checks on every session-scoped endpoint.** "Is the user logged in" is not enough — every read/write on a `Workshop`/session resource must verify the authenticated user owns (or is authorized on) that specific resource ID, not just that *a* valid session token was presented. Add a test that user A cannot GET/PUT/DELETE user B's session by guessing/incrementing an ID.
3. **No raw SQL string concatenation.** Audit for any `@Query(nativeQuery = true)` or JDBC template usage that builds SQL via string concatenation with user input. All queries must use bind parameters (`:param` / `?`), never string interpolation.
4. **No direct DB read path for clients.** Confirm there's no admin/debug endpoint, actuator endpoint, or GraphQL-style query surface that lets a client read arbitrary tables/columns. Lock down Spring Boot Actuator endpoints (or remove them from the public profile) if enabled.
5. **Input validation on all DTOs.** Use Bean Validation (`@NotNull`, `@Size`, `@Pattern`, etc.) on request DTOs so malformed or oversized payloads (e.g. absurd numbers of learning goals, huge text blobs) are rejected before reaching business logic — reinforces the "capped at 5-7 elements" UX rule server-side, not just client-side.
6. **Rate limiting on LLM-calling and export endpoints.** Even basic per-user/per-IP rate limiting (bucket4j or a simple in-memory limiter is fine at this scale) prevents accidental or malicious hammering of the most expensive endpoints.
7. **Secrets out of source.** Confirm LLM API keys / DB credentials are only in environment variables or a secrets manager, never committed, and that `.env`/local config files are gitignored.
8. **CORS locked to known origins** in production config, not `*`.

---

## Phase 6 — Tests (prioritized by risk)

Priority order — do not spend equal effort everywhere; the DB/authz layer is highest risk.

1. **Highest priority — authorization & data integrity:**
   - Test that cross-user access to sessions is rejected (see Phase 5.2).
   - Test that no endpoint allows SQL injection via crafted input (parameterized test with injection-style payloads against any free-text fields, expect them treated as literal data).
   - Test idempotency/duplicate-submit behavior (Phase 4.2).
2. **Core feature correctness (use case unit tests):**
   - One unit test per use case introduced in Phase 2, covering the main success path and at least one failure/edge path (e.g. LLM returns malformed structured output — should fail gracefully, not throw an unhandled exception to the client).
3. **Integration tests:**
   - Testcontainers-backed tests for repository queries and the full controller → facade → use case → repository path for the core flows (generate timetable, generate slides, export).
4. **Frontend:**
   - React Query hooks tested with MSW (Mock Service Worker) to simulate API responses/errors.
   - Component tests for the wizard steps' validation logic (rejecting invalid/incomplete input before allowing "Next").
   - Keep the Phase 0 e2e smoke tests running in CI going forward.

Wire these into CI (GitHub Actions or whatever the monorepo already uses) so they run on every PR, not just locally.

---

## Phase 7 — Documentation (for humans and agents)

Create/update:

1. **`workshopper/README.md`** (human-facing): what the app does, tech stack, how to run it locally (frontend + backend + DB), how to run tests, how to run migrations, environment variables required.
2. **`workshopper/ARCHITECTURE.md`**: the Feature-Sliced Design layout on the frontend and Use-Case/Facade layout on the backend, with a short "why" referencing this migration (evolutionary from monolithic → feature-sliced). Include a diagram if easy (even ASCII) showing Controller → Facade → Use Case → Repository, and Wizard → Zustand store → React Query → API.
3. **`workshopper/AGENTS.md`** (or `CLAUDE.md`, agent-facing): explicit conventions for a coding agent working in this repo —
   - where new features go (which feature folder / which use-case pattern to follow)
   - naming conventions for use cases, hooks, stores
   - "never do this": no raw SQL, no prompts built inline in services, no business logic in controllers, no bypassing the ownership check pattern
   - how to run the test suite and what must pass before a change is considered done
   - pointer to `ARCHITECTURE.md` for the "why"
4. **API documentation**: add/update OpenAPI/Swagger annotations on controllers so the REST surface is self-describing; expose the Swagger UI in local/dev profiles only.
5. **Inline documentation**: Javadoc on use case public methods (what it does, inputs, failure modes) and JSDoc/TSDoc on non-trivial hooks and store actions. Skip documenting trivial getters/setters — focus on decision points and anything non-obvious (e.g. why slide generation is chunked per block instead of whole-session).

---

## Suggested execution order (summary)

0. Safety-net tests against current code
1. Externalize prompts + adopt structured output converter
2. Backend: use cases + facade + repository query audit
3. Frontend: Zustand + React Query + feature-sliced reorg
4. Misuse resilience (idempotency, server-side state validation, draft persistence)
5. Security hardening (authz, injection audit, rate limiting, secrets)
6. Fill out risk-prioritized test suite, wire into CI
7. Documentation pass (README, ARCHITECTURE.md, AGENTS.md, OpenAPI, inline docs)

After each numbered phase: run the full test suite, commit, and write a one-paragraph summary of what changed and what was verified before moving to the next phase.

# AGENTS.md

This file defines repository-specific instructions for AI coding agents working on Workshopper. It extends the root `AGENTS.md`.

## Language
Use English for repository documentation and agent-facing instructions.

## Product Focus
Core Workshopper concepts:
- pedagogical session structuring with block phases ARRIVE, ACTIVATE, LEARNING_CYCLE, BREAK, EVALUATE, SUMMARY, BUFFER
- dynamic duration management; activity choice per learning goal from its Bloom's/SOLO level, limited to the activities the lecturer selected (`ActivityRecommender` on the server, `activity-taxonomy.ts` on the client)
- learning goals entered by the lecturer or imported from the Learning Goal Hub, with per-goal AI checks (`RefineLearningGoalUseCase`) and a grammar fix (`FixGoalsGrammarUseCase`)
- document generation: Apache POI for PPTX (default design, optionally branded with an uploaded university template), Apache PDFBox for PDF
- courses that group sessions and share settings and a slide template
- session persistence, draft management and unsaved-changes protection in the wizard

## Technical Direction
- server: Spring Boot 3.5 (Java 21), Gradle module `:apps:workshopper:server`
- LLM integration: Spring AI via Hestia's `libs:shared-llm` module (TUM AET Logos gateway, key `WORKSHOPPER_LOGOS_API_KEY`); prompts are `.st` files in `server/src/main/resources/prompts`
- database: PostgreSQL with Flyway migrations (`server/src/main/resources/db/migration`, V1–V8); never edit an applied migration, add a new one
- client: React, Vite, Tailwind CSS; wizard state lives in `client/src/hooks/useWizardState.ts` (plain React state)
- build system: Gradle (multi-module)

---

## STRICT AGENT RULES

### 1. Evidence-Backed Verification (The "Grep Rule")
**Never claim a task is "verified", "complete", or "fully migrated" based on assumption.**
If you say a migration is complete, prove it by showing the `grep` results that confirm zero remaining old references. If you say a bug is fixed, prove it with passing test output (`./gradlew :apps:workshopper:server:test` from the repository root, or `npm test` and `npx tsc --noEmit` in `client/`).
*Do not let an unverified claim into your output.*

### 2. Contract Mismatch Awareness
When modifying DTOs (`RequestDto` / `ResponseDto`), trace the data flow through the Facade into the `UseCase`, and check the matching TypeScript types in `client/src/lib/workshop-generator.ts`.
If the client sends `availableMaterials`, ensure it isn't silently dropped by the Facade before reaching `GenerateTimetableUseCase`. The compilers will not catch dropped or renamed JSON fields.

### 3. Layering
- Controllers never call a `UseCase` directly; generation goes through `WorkshopSessionFacade`.
- Persistence (sessions, courses, drafts, templates) goes through `WorkshopService`; it holds no generation logic.
- Every endpoint that touches a session or course by ID checks ownership (`facade.verifyOwnership` / `facade.verifyCourseOwnership`).

### 4. Auth Bypass Awareness
Local development and the VPN-only `hestia-test` VM use the `local` Spring profile, which activates `DevAuthFilter` and signs every request in as `dev-local-user` (SAML access exists only for production). There is no default profile; `local` must be chosen explicitly and never be active in production.
- NEVER attempt to disable CSRF globally or write manual `catch (AccessDeniedException e)` blocks to fix auth issues.
- Rely on `ApiExceptionHandler` (403/404/409/500 for every controller) and the existing SAML 401 redirect logic in `api.ts`. Controllers need no `try`/`catch`; let exceptions propagate.

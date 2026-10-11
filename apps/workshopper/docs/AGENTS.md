# AGENTS.md

This file defines repository-specific instructions for AI coding agents working on Workshopper. It extends the root `AGENTS.md`.

## Language
Use English for repository documentation and agent-facing instructions.

## Product Focus
Core Workshopper concepts:
- pedagogical session structuring (ARRIVE, ACTIVATE, INFORM, PROCESS, BREAK, EVALUATE, SUMMARY)
- dynamic duration management and LLM-driven activity selection
- LLM-powered extraction of learning goals from unstructured materials (PDF/TXT)
- automated document generation (Apache POI for PPTX, Apache PDFBox for PDF)
- session persistence and interactive draft management

## Technical Direction
- backend: Spring Boot (Java 21)
- LLM integration: Spring AI (via Hestia's `libs:shared-llm` module)
- database: PostgreSQL
- frontend: React, Vite, Tailwind CSS
- build system: Gradle (multi-module)

---

## 🛑 STRICT AGENT RULES

### 1. Evidence-Backed Verification (The "Grep Rule")
**Never claim a task is "verified", "complete", or "fully migrated" based on assumption.**
If you say a migration is complete, you must prove it by showing the `grep` results that confirm zero remaining old references. If you say a bug is fixed, you must prove it by showing the passing test output (`./gradlew test` or `npx playwright test`).
*Do not let an unverified claim into your output.*

### 2. Contract Mismatch Awareness
When modifying DTOs (`RequestDto` / `ResponseDto`), you must manually trace the data flow through the Facade into the `UseCase`.
If the frontend sends `availableMaterials`, ensure it isn't silently dropped by the Facade before reaching `GenerateTimetableUseCase`. The backend compiler will not catch dropped fields.

### 3. Strangler Pattern Enforcement
The backend has migrated away from a monolithic `WorkshopService` to a UseCase pattern.
**Rule:** Controllers (`WorkshopController`) may ONLY call `WorkshopSessionFacade`. Controllers may NEVER call a `UseCase` directly. `WorkshopService` is now legacy and should only be used for CRUD operations, not generation logic.

### 4. Auth Bypass Awareness
Local development and `hestia-test` use the `local` Spring profile, which activates `DevAuthFilter` and injects `dev-local-user`. 
- NEVER attempt to disable CSRF globally or write manual `catch (AccessDeniedException e)` blocks to fix auth issues. 
- Rely on the global `@ExceptionHandler` and the existing SAML 401 redirect logic in `api.ts`.

# Workshopper Architecture

This document captures the core architectural decisions and boundaries of the Workshopper application, ensuring future developers (and AI agents) understand *why* things are built the way they are.

## 1. Backend: The UseCase & Facade Pattern
Originally, all generation logic (LLM calls, PPTX building, DB saving) lived in a massive, monolithic `WorkshopService.java`. This made testing impossible and caused transaction timeouts.

We migrated to a strict **UseCase Architecture**:
* **`UseCase` Classes** (e.g., `GenerateTimetableUseCase`): Highly focused, single-responsibility classes that perform one specific business action. They do not handle HTTP, and they generally do not handle database transactions.
* **`WorkshopSessionFacade`**: The orchestrator. It receives DTOs from the Controller, verifies ownership, fetches necessary entities from `WorkshopSessionRepository`, and passes them to the UseCases.
* **`WorkshopController`**: The HTTP boundary. It handles routing and immediately delegates to the Facade. *It must never call a UseCase directly.*
* **`WorkshopService`**: Now relegated to a simple CRUD service for fetching/saving database entities.

## 2. Security & Environments (The Profile Gate)
Workshopper integrates with TUM Central IT via SAML 2.0. However, developing locally against real SAML is painful. 

To solve this, we built a secure bypass:
* **`DevAuthFilter`**: Active *only* when the `local` Spring profile is active. It intercepts all requests and injects a fake `dev-local-user` principal.
* **The Profile Gate**: In `SecurityConfig.java`, there is a hard gate that checks for `KUBERNETES_SERVICE_HOST` and `FLY_APP_NAME`. If the app is deployed to a real orchestration environment and the `local` profile is active, the app will instantly crash. This guarantees the bypass can never accidentally run in production.
* **Environments**: 
  * `local` (Laptop): Uses `application.yml` default (`local`). SAML bypassed.
  * `hestia-test` (VM): Injects `SPRING_PROFILES_ACTIVE=local` via `.env`. SAML bypassed safely (not K8s).
  * `hestia-prod` (VM): Defaults to `prod`. Real SAML enforced.

## 3. API Authorization & Error Handling
All `/api/**` endpoints (except `/health`) require an authenticated principal (`.authenticated()`). 
When an unauthenticated request hits the API, Spring returns a `401 Unauthorized`.
In the frontend (`api.ts`), the `handleAuthError` interceptor catches all 401s and automatically mutates `window.location.href` to `/saml2/authenticate/tum`, seamlessly triggering the SAML login flow.

## 4. Frontend State
The frontend previously suffered from a monolithic `App.tsx` file (800+ lines) that handled all routing, session fetching, and dozens of parallel `useState` hooks for the creation wizard.

**Migration Completed (Phase 5):**
The `useWizardState.ts` hook was fully synchronized with live fixes (including SAML `401` traps) and wired into `App.tsx`. `App.tsx` is now a clean layout/routing wrapper that simply destructures state and step-handlers from the hook. Furthermore, complex UI components within the timetable view (like `EvaluateMappingPanel` and `UnderstandingCheckSteps`) have been extracted from monolithic rows into dedicated components.

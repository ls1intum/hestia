# AGENTS.md

This file defines repository-specific instructions for AI coding agents working on the
HESTIA landing page.

## Language

Use English for repository documentation and agent-facing instructions. The page's UI is
**bilingual DE/EN** (`src/i18n/de.ts` is the source of truth, `en.ts` must match — the
`Dictionary` type enforces parity). Keep both dictionaries in sync and do not rephrase
approved copy as a side effect of refactoring.

## Project Context

This is the **root landing page of the HESTIA project** — a standalone, frontend-only React
SPA (no backend, no database) served at the bare host root (`https://<vm-host>/`) of the
HESTIA VMs, in front of the thesis apps (ExamLense, LearningGoalHub, Workshopper).

It presents the HESTIA vision (Constructive Alignment — good teaching in the AI era),
previews the tool pipeline, and collects newsletter signups and teaching-material
donations. The Listmonk newsletter, Nextcloud upload, contact email and Impressum are now
wired (via `src/config.ts` and the hash route `#/impressum`). The remaining unwired links
(Datenschutz, Datenkonzept) are still marked with visible "Platzhalter" badges.

## Required Agent Workflow

For non-trivial changes, follow:

Explore → Plan → Code → Verify → Summarize

Before editing:

- inspect the relevant files
- identify affected areas
- state the intended plan
- mention the verification commands

For unclear tasks, do an exploration-only pass first. Do not edit files until the plan is
clear.

## Technical Direction

Use the established technology stack unless explicitly changed:

- React 18 + TypeScript, Vite (SWC), Tailwind CSS 3
- runtime dependencies are **only** `react` and `react-dom` — do not add a router, state
  library, or component library without an explicit request
- styling exclusively through the HESTIA design tokens (CSS variables in `src/index.css`,
  mapped to Tailwind utilities in `tailwind.config.ts`); derive states with `color-mix`,
  never invent new hex values
- external integrations are wired only through `src/config.ts` (`VITE_` env vars); when a
  real endpoint lands, remove the corresponding `PlaceholderBadge` at the call site
- deployment: single nginx container, root catch-all Traefik router at priority 1 — no
  path prefix, Vite base stays `/` (see `DEPLOY.md`)

## Verification

There is no test suite. For every change run, from `apps/landing-page/`:

```bash
npm run lint
npm run build
```

and check the page visually (`npm run dev`, http://localhost:8090) in **both themes**
(header toggle) and at mobile width. Docker changes: `docker build .` must succeed and the
container must serve `/` and deep links with 200 (SPA fallback).

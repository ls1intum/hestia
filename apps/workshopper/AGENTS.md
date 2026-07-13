# AGENTS.md

This file defines repository-specific instructions for AI coding agents working on Workshopper.

## Language

Use English for repository documentation and agent-facing instructions.

## Project Context

The system helps instructors generate interactive, pedagogically sound workshop sessions and lecture slides based on specified learning goals and uploaded educational materials. It utilizes LLMs to structure a session into logical phases, propose activities, estimate durations, and output complete PDF handouts and PPTX presentations.

## Required Agent Workflow

For non-trivial changes, follow:

Explore → Plan → Code → Verify → Summarize

Before editing:

- inspect the relevant files
- identify affected areas
- state the intended plan
- mention the verification commands

For unclear tasks, do an exploration-only pass first. Do not edit files until the plan is clear.

## Product Focus

Core Workshopper concepts:

- pedagogical session structuring (e.g. ARRIVE, ACTIVATE, INFORM, PROCESS, BREAK, EVALUATE, SUMMARY)
- dynamic duration management and LLM-driven activity selection
- LLM-powered extraction of learning goals from unstructured materials (PDF/TXT)
- automated document generation (Apache POI for PPTX, Apache PDFBox for PDF)
- session persistence and interactive draft management

## Technical Direction

Use the established technology stack unless Yingge explicitly changes them.

Current high-level direction:

- backend: Spring Boot (Java 21)
- LLM integration: Spring AI (via Hestia's `libs:shared-llm` module)
- database: PostgreSQL
- frontend: React, Vite, Tailwind CSS
- build system: Gradle (multi-module)

Authentication is not the primary implementation responsibility. Do not spend implementation effort on auth unless explicitly requested.

## LLM and Export Rules

LLM interactions and file generation must be robust.

Rules:

- prefer structured JSON output formats for LLM prompts and ensure fallback parsing logic
- preserve pedagogical alignment (goals must map directly to session activities)
- use `libs:shared-llm` for foundational LLM defaults rather than standalone config
- treat PPTX and PDF export styling carefully, avoiding unchecked raw text that overflows pages
- avoid real LLM calls in automated CI tests

## Verification

Before reporting work as done, run the narrowest relevant verification.

Examples:

- backend tests: `./gradlew :apps:workshopper:backend:test`
- frontend typecheck/lint/build: `npm run build` or `npm run dev` in `apps/workshopper/frontend/`
- container build: `docker compose build` 

If verification cannot run in the current environment, state why and provide the exact command that should be run.
Never claim tests passed unless they actually ran.

## Commits

Use Conventional Commits for commit messages. Since Workshopper lives in the `ls1intum/hestia` monorepo alongside parallel theses, always include the `(workshopper)` scope.

Examples:

- `feat(workshopper): add drag-and-drop session reordering`
- `fix(workshopper): handle missing LLM JSON response`
- `docs(workshopper): update deployment instructions`
- `chore(workshopper): bump frontend dependencies`

## Branches and Pull Requests

Use short-lived feature branches for non-trivial changes.

Commit directly to `main` only for small documentation or housekeeping changes.

Open a pull request when a branch is ready for review or before merging it into `main`.

Each pull request description should include:

- What changed and why
- How it was tested

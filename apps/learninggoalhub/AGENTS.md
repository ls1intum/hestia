# AGENTS.md

This file defines repository-specific instructions for AI coding agents working on LearningGoalHub.

## Language

Use English for repository documentation and agent-facing instructions.

## Project Context

LearningGoalHub is Florian's master's thesis project.

The system helps instructors extract, review, edit, merge, and manage learning goals from educational materials. It uses LLM-based analysis, organizes learning goals across a module → session → exercise hierarchy, classifies them with Bloom's revised taxonomy and the SOLO taxonomy, and provides APIs consumed by related thesis projects such as ExamLens and Workshopper.

For domain context, read:

- `docs/proposal/objective.typ`
- `docs/proposal/schedule.typ`

Do not copy large parts of the proposal into code or docs unless explicitly requested.

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

Core LearningGoalHub concepts:

- educational hierarchy: module → session → exercise
- learning goals attached to one or more hierarchy levels
- Bloom taxonomy classification
- SOLO taxonomy classification
- source attribution to uploaded educational material
- instructor review/edit/merge workflows
- goal relationships such as:
  - contributes-to
  - prerequisite-of
  - overlaps-with

## Technical Direction

Use the technology choices described in the proposal unless Florian explicitly changes them.

Current high-level direction:

- backend: Spring Boot
- LLM integration: Spring AI
- database: PostgreSQL
- frontend: React and Tailwind CSS
- API style: REST with OpenAPI documentation

Authentication is not Florian's implementation responsibility. Do not spend implementation effort on auth unless explicitly requested.

Do not introduce a different frontend framework or styling system unless explicitly requested.

## LLM and Extraction Rules

LLM extraction must be reproducible and evaluable.

Rules:

- prefer structured output over free-text parsing
- preserve source attribution for extracted goals
- keep prompt/model/provider choices explicit where relevant
- avoid real LLM calls in automated CI tests
- use mocked or recorded responses for automated tests where possible

## API Notes

The API is consumed by related thesis projects. Treat breaking API changes as expensive.

Prefer:

- REST endpoints
- JSON request/response bodies
- OpenAPI documentation
- additive changes over breaking changes
- clear error responses

## Verification

Before reporting work as done, run the narrowest relevant verification.

Examples:

- backend tests when backend code changes
- frontend typecheck/lint/build when frontend code changes
- documentation build when Typst/proposal files change
- API documentation generation/checks when API contracts change

If verification cannot run in the current environment, state why and provide the exact command that should be run.

Never claim tests passed unless they actually ran.

## Commits

Use Conventional Commits for commit messages. Since LearningGoalHub lives in the `ls1intum/hestia` monorepo alongside parallel theses, always include the `(learninggoalhub)` scope.

Examples:

- `feat(learninggoalhub): add learning goal review view`
- `fix(learninggoalhub): handle missing learning goal title`
- `docs(learninggoalhub): update setup instructions`
- `chore(learninggoalhub): update project configuration`

## Branches and Pull Requests

Use short-lived feature branches for non-trivial changes.

Commit directly to `main` only for small documentation or housekeeping changes.

Open a pull request when a branch is ready for review or before merging it into `main`.

Each pull request description should include:

- What changed and why
- How it was tested

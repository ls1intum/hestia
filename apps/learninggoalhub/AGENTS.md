# AGENTS.md

This file defines repository-specific instructions for AI coding agents working on LearningGoalHub.

## Language

Use English for repository documentation and agent-facing instructions.

## Project Context

LearningGoalHub is Florian's master's thesis project.

The system helps instructors extract, review, edit, merge, and manage learning goals from educational materials. It uses LLM-based analysis, organizes learning goals across a module → session → exercise hierarchy, classifies them with Bloom's revised taxonomy and the SOLO taxonomy, and provides APIs consumed by related thesis projects such as ExamLens and Workshopper.

## Required Agent Workflow

For non-trivial changes, follow:

Explore → Plan → Code → Verify → Summarize

Before editing:

- inspect the relevant files
- identify affected areas
- state the intended plan
- mention the verification commands

For unclear tasks, do an exploration-only pass first. Do not edit files until the plan is clear.

## Orchestration and Delegation

You are the orchestrator. Plan, decompose, synthesize.

- Reasoning-heavy phases → `deep-reasoner`
- Mechanical work → `mechanical-task-executor`
- Codex (`/codex:rescue --background`) is a cracked engineer on par with `deep-reasoner`, from a different perspective. Treat as a peer, not a reviewer.
- High-stakes decisions: task Opus + Codex on the same problem in parallel, synthesize the best of both, without showing either the other's answer. Keep your own context lean.

## 1. Think Before Coding

Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

Minimum code that solves the problem. Nothing speculative.

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

Touch only what you must. Clean up only your own mess.

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

Define success criteria. Loop until verified.

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

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

Use the technology stack below unless Florian explicitly changes it.

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

## Never Commit

- secrets or credentials — only `.env.example` templates; real values live in gitignored `.env` files
- personal data (usernames, emails, private hostnames)
- copyrighted material (lecture slides/PDFs); test fixtures must be synthetic or freely licensed
- user-specific details in docs — keep repository docs user-agnostic
- never use `git add -A`; stage files by explicit path

## Repo Guard

The repo ships a pre-commit guard (secret scan via gitleaks + binary file guard). One-time setup per clone:

```bash
git config core.hooksPath .githooks
```

The hook requires `gitleaks` (e.g. `brew install gitleaks`). The same checks run in CI on every pull request (`.github/workflows/repo-guard.yml`).

Never bypass the hook (`--no-verify`). For a deliberate, reviewed binary exception, add the exact path to `.githooks/binary-allowlist` in the same commit.

## Verification

Before reporting work as done, run the narrowest relevant verification.

Examples:

- backend tests when backend code changes
- frontend typecheck/lint/build when frontend code changes
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

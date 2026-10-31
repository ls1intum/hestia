# AGENTS.md

Instructions for AI coding agents working on LearningGoalHub. They extend the repository's root
[AGENTS.md](../../AGENTS.md) (never commit secrets or personal data, the repo guard, commit
scopes), which applies as well.

## Language

Use English for repository documentation and agent-facing instructions.

## Project Context

LearningGoalHub is a master's thesis project. It helps instructors extract, review, edit and
manage learning goals from course materials. It uses LLM-based analysis, organizes learning goals
in a Topic tree, classifies them with Bloom's revised taxonomy and the SOLO taxonomy, and provides
APIs consumed by the related thesis projects ExamLens and Workshopper.

## Read First

| Before you … | read |
| --- | --- |
| run, test or configure anything | [README.md](README.md) |
| change server code | [server/README.md](server/README.md): packages, conventions, how to add an endpoint, an LLM call or a prompt |
| change client code | [docs/CLIENT.md](docs/CLIENT.md): which component renders what, query keys, tier names, styling |
| change something that spans server and client (a goal field, a course setting, an extraction phase, a page) | the checklist in [docs/EXTENDING.md](docs/EXTENDING.md) |
| touch a model call or a prompt | [docs/LLM.md](docs/LLM.md) |
| change an endpoint other apps use | [docs/api.md](docs/api.md) |
| change security, keys or limits | [docs/SECURITY.md](docs/SECURITY.md) |
| change the database | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#data-model) |

The LLM defaults come from `libs/shared-llm`, which Workshopper uses too. Read
`libs/shared-llm/AGENTS.md` before changing it.

## Rules That Are Easy to Miss

These are the mistakes the compilers and most tests do not catch.

1. **Tier names differ between UI, client, server and API.** The UI says Topic → Skill → Sub-skill →
   Knowledge. The server calls the Skill tier "capability" and the Sub-skill tier "skill", and the
   API gives skills and sub-skills the same `role: SKILL` (the position in the tree tells them
   apart). The tree itself is the "competency tree" in older code and the API
   (`POST /competency-tree`, `HierarchyLevel.COMPETENCY`). Mapping:
   [docs/api.md](docs/api.md#topic-tree). In the client, use the `TreeRole` that `buildTopicForest`
   (`lib/goals.ts`) gives each row, not the goal's API `role`.
2. **No model call inside a database transaction.** A call can take minutes and holds row locks the
   whole time. Read in one transaction, call the model, write in another; take
   `CourseRepository.lockById` when two requests may write the same course
   ([conventions](server/README.md#conventions)).
3. **The client's API types come from a checked-in snapshot.** After changing a request or response
   record or an endpoint, regenerate `client/openapi/openapi.json` and `client/src/api/schema.d.ts`
   with the command in [client/README.md](client/README.md#api-client) and commit both;
   `OpenApiSnapshotTest` fails until you do. A status code other than the inferred one (`202`,
   `204`, `409`) needs an `@ApiResponse`. Records are positional: `grep` for every `new ...(` call.
4. **Other theses depend on the API.** ExamLens and Workshopper read `LearningGoalResponse`, the
   goal endpoints and the exam-task endpoint. Add fields; do not rename or remove them. Document
   additions in [docs/api.md](docs/api.md). Errors keep the `{code, message}` body from
   `GlobalExceptionHandler`; throw `ResponseStatusException` instead of building error responses.
5. **A new prompt shape needs the fake model.** The browser tests answer every model call from
   `client/e2e/fake-llm.mjs`, which recognizes a prompt by the JSON it asks for. A new or changed
   shape fails the e2e suite with the fake's `400` until `answer()` knows it.
6. **Prompts carry versions.** Bump `SessionExtractionService.PROMPT_VERSION` or
   `PageDescriptionService.FIGURE_PROMPT_VERSION` when you change those prompts, so stored runs show
   which prompt produced them.
7. **Endpoints that start model calls are rate-limited by path.** Add a new one to
   `SecurityConfig.LLM_REQUESTS`, and to `SERVICE_ONLY` if only consuming servers call it.
8. **Package layers are enforced.** `PackageCycleTest` (ArchUnit) fails on a dependency cycle
   between packages. Move the shared piece down instead of importing upward.
9. **Migrations are append-only.** Add `V<next>__<name>.sql`; never edit a merged one. Hibernate
   only validates the schema, so an entity change without a migration fails at startup.
10. **Legacy values stay.** `SUPPORTS`, `PREREQUISITE_OF`, `OVERLAPS_WITH`, `GAP` and the embedding
    column are kept for old data. New code does not produce them; do not delete them.
11. **The diagram layout is wired by hand.** An attribute missing from `DiagramAttributes` passes
    typecheck and the tests; the field is just absent from the diagram
    ([EXTENDING.md](docs/EXTENDING.md#add-a-field-to-a-learning-goal), step 10).
12. **Theme colours are CSS variables.** Use the `hestia-*` Tailwind colours from `index.css`,
    not fixed colours or `dark:` variants ([docs/CLIENT.md](docs/CLIENT.md#styling)).

## Required Agent Workflow

For non-trivial changes, follow:

Explore → Plan → Code → Verify → Summarize

Before editing:

- inspect the relevant files
- identify affected areas
- state the intended plan
- mention the verification commands

For unclear tasks, do an exploration-only pass first. Do not edit files until the plan is clear.

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

- uploaded PDFs (lecture or exercise) split into sessions along their bookmarks, under a module → session → exercise hierarchy
- the Topic tree: Topic → Skill → Sub-skill → Knowledge, stored as `CONTRIBUTES_TO` relationships between goals
- Bloom taxonomy classification
- SOLO taxonomy classification
- source attribution: extracted goals quote the page they came from
- instructor review: edit, delete, add, approve, "Find in the slides", "Generate with AI"
- exam goals derived from ExamLens tasks and placed in the Topic tree

## Technical Direction

Use this stack unless the project owner explicitly changes it:

- server: Spring Boot 3.5, Java 21
- LLM integration: Spring AI through the app's single `LlmClient`, against the TUM AET Logos gateway
- database: PostgreSQL with Flyway migrations
- client: React, TypeScript and Tailwind CSS (Vite)
- API style: REST with OpenAPI documentation

Say "server" and "client", not "backend" and "frontend".

The SAML login is integrated separately and is out of scope unless a task asks for it (see
[docs/SECURITY.md](docs/SECURITY.md)). Service keys and the LLM rate limit in `config/` are in
scope.

Do not introduce a different client framework or styling system unless explicitly requested.

## LLM and Extraction Rules

LLM extraction must be reproducible and evaluable.

- prefer structured output (a record via `LlmClient.entity`) over free-text parsing
- preserve source attribution for extracted goals
- read model and temperature from `application.yml`, not from constants
- validate a reply before storing it; count what you drop instead of failing silently
- no real model calls in automated tests: mock `LlmClient`, or write a `*LiveIT` gated on
  `RUN_LIVE_LOGOS=1`

## Verification

Before reporting work as done, run the narrowest relevant verification. Commands, from the
repository root:

- server: `./gradlew :apps:learninggoalhub:server:test` — needs a running Docker (Testcontainers). A run that ends within seconds came from the Gradle cache; add `--rerun-tasks`. If it stalls with no results, check that `docker ps` answers.
- client: `cd apps/learninggoalhub/client && npm run lint && npm run typecheck && npm test`
- browser tests: `cd apps/learninggoalhub/client && npm run e2e` — needs a Postgres on port 5433 and the built server jar (see `client/README.md#browser-tests`). Run them after changing a user workflow or a prompt's JSON shape.
- API contract changes: update the client types (rule 3) and `docs/api.md` for endpoints other apps use.
- docs: if your change moves a class, component or setting that a doc names, update that doc in the same change. `grep -rn '<OldName>' apps/learninggoalhub --include='*.md'` finds them.

Booting the server locally needs a throwaway SAML key pair and a non-empty `LOGOS_API_KEY` (see `README.md`). Flyway migrates the database it connects to on startup; point a branch with new migrations at a separate database, not at one holding data you need.

Back every "done" with evidence: the test output that passed, or the `grep` that shows no old
reference is left. If verification cannot run in the current environment, say why and give the
exact command to run. Never claim tests passed unless they actually ran.

## Commits and Pull Requests

Conventional Commits with the `(learninggoalhub)` scope, for example
`feat(learninggoalhub): add learning goal review view` or
`docs(learninggoalhub): update setup instructions`. Work on a short-lived branch and open a pull
request against `main`; `main` is protected. Each pull request description says what changed and
why, and how it was tested.

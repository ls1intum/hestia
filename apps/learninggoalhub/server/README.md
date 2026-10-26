# LearningGoalHub Server

Spring Boot 3.5 on Java 21, with Spring AI for model calls, PostgreSQL through Spring Data JPA, and
Flyway for the schema. To run it locally, see the [app README](../README.md#run-locally).

```bash
./gradlew :apps:learninggoalhub:server:test      # from the repository root; needs Docker
./gradlew :apps:learninggoalhub:server:bootRun   # needs the env vars from the app README
```

## Packages

Everything lives under `de.tum.cit.hestia.learninggoalhub`. Each package owns its entities,
repositories, services and controllers. The packages form layers, and `PackageCycleTest` (ArchUnit)
fails the build when two of them depend on each other.

| Package | Responsibility | Main classes |
| --- | --- | --- |
| `llm` | the single gateway to the chat models: retry, HTTP timeouts, usage logging, lenient JSON binding | `LlmClient`, `LenientJson`, `AiRetryConfiguration` |
| `course` | courses, their settings and extraction status | `CourseController`, `CourseService` |
| `document` | upload, PDF parsing (Tika, PDFBox), sessions from bookmarks, language detection, figure descriptions, highlight geometry | `DocumentService`, `DocumentParser`, `DocumentStructureService`, `PageDescriptionService` |
| `hierarchy` | the module → session → exercise nodes goals hang off | `HierarchyNode`, `HierarchyNodeController` |
| `taxonomy` | Bloom and SOLO levels; the classification call for goals created outside extraction (edits, exam goals, Topic tree) | `TaxonomyService` |
| `goal` | learning goals, sources, relationships; the read, edit and CSV endpoints | `LearningGoalController`, `LearningGoalQueryService`, `LearningGoalEditService` |
| `topictree` | builds the Topic tree from the extracted goals and places exam goals in it | `TopicTreeService`, `TopicTreeSynthesizer`, `ExamGoalPlacer` |
| `extraction` | the asynchronous pipeline, progress tracking, run audit, "Find in the slides" | `ExtractionRunner`, `UnitExtractor`, `SessionExtractionService`, `TopicSearchService` |
| `exam` | the ExamLens endpoint: goals for exam tasks | `ExamGoalController`, `ExamGoalService`, `ExamGoalGenerator` |
| `config` | security (SAML, service keys) and the LLM rate limit | `SecurityConfig`, `ServiceApiKeyFilter`, `LlmRateLimitFilter` |
| `web` | the `{code, message}` error body | `GlobalExceptionHandler` |

The Topic tree is called the competency tree in older code and in the API
(`POST /competency-tree`, `HierarchyLevel.COMPETENCY`, `CompetencyTreeResult`).
[docs/api.md](../docs/api.md#topic-tree) maps the API values to the UI tiers.

## An extraction run

`POST /api/courses/{id}/extract` returns `202` and hands the work to `ExtractionRunner.runForCourse`
on a background thread:

1. **Read** the course and its documents in a short transaction and record an `extraction_run` row.
2. **Extract** with no transaction open. `UnitExtractor` cuts sessions into units of at most
   `hestia.extraction.unit-max-chars` characters. `SessionExtractionService` makes one structured
   call per unit, up to `hestia.extraction.parallelism` at a time, which returns the skills, their
   knowledge, Bloom and SOLO levels and source quotes. `ExtractionProgressTracker` keeps the
   progress the client polls at `/extract/status`.
3. **Persist** goals, sources and hierarchy nodes in one transaction. A forced re-extraction deletes
   the old results in that same transaction, so a failed run leaves the previous extraction intact.
4. **Build the Topic tree** in `TopicTreeService`: read in one transaction, plan with the model while
   no transaction is open, write in a second transaction under the course row lock.

Prompts carry a version (`SessionExtractionService.PROMPT_VERSION`,
`PageDescriptionService.FIGURE_PROMPT_VERSION`) that is stored with each run, so results stay
comparable across prompt changes.

## Conventions

- **No model call inside a transaction.** A call can take minutes and would hold row locks the whole
  time. Read in one transaction, call the model, write in another (`TransactionOperations.execute`).
  When two requests may write the same course, take `CourseRepository.lockById` in the write
  transaction.
- **Errors.** Throw `ResponseStatusException` with a 4xx status for client mistakes.
  `GlobalExceptionHandler` turns every exception into `{code, message}`; an unhandled one becomes a
  logged `500`. Do not catch and swallow: log a degraded result and count it (see
  `extraction_run.dropped_outcomes`).
- **Schema changes** go into a new `src/main/resources/db/migration/V<n>__<name>.sql`. Hibernate only
  validates the schema (`ddl-auto: validate`), so an entity change without a migration fails at
  startup. Never edit a migration that has been merged.
- **Open-in-view is off**, in production and in the tests. Load what a response needs inside the
  service's transaction, not in the controller.

## How to add …

### an endpoint

1. Put the controller method in the package that owns the resource and keep it thin: validate the
   request, call a service, map to a response record.
2. Keep the logic in a service of the same package. If you need a class from a higher layer, move
   the shared piece down instead; `PackageCycleTest` tells you when you got it wrong.
3. If the endpoint starts model calls, add its path to `SecurityConfig.LLM_REQUESTS` so the rate
   limit covers it. If only consuming servers call it, add it to `SERVICE_ONLY` too.
4. Write a `@SpringBootTest` + `MockMvc` test with `@Import(TestcontainersConfiguration.class)` and
   mock the model-calling bean with `@MockitoBean`, as `ExamGoalControllerTest` does.
5. Regenerate the client types (see [client/README.md](../client/README.md#api-client)) and commit
   `openapi.json` and `schema.d.ts` with the change. For endpoints other apps use, update
   [docs/api.md](../docs/api.md). Prefer additive changes there; ExamLens and Workshopper depend
   on the current shapes.

### an LLM call

1. Inject `LlmClient`; do not build a `ChatClient` yourself. `LlmClient` applies retries, timeouts
   and usage logging to every call.
2. Build an `LlmRequest` (`LlmRequest.of(prompt).withSystem(...).withModel(...).withTemperature(...)`)
   and call `entity(request, MyRecord.class)` for structured output, or `text(request)`. Prefer a
   record over parsing free text.
3. Read the model and temperature from `application.yml` (`@Value("${hestia....}")`), not from
   constants, so a run's settings are visible and can be overridden.
4. Validate the reply before storing it. Models drop fields and return out-of-range values.
5. Test with a mocked `LlmClient` or a mocked `ChatClient.Builder` (`ExamGoalGeneratorTest`). A test
   that needs a real model is a `*LiveIT` guarded by
   `@EnabledIfEnvironmentVariable(named = "RUN_LIVE_LOGOS", matches = "1")`.

### or change a prompt

Prompts are Java text blocks next to the call that uses them (for example
`ExamGoalGenerator.PROMPT_TEMPLATE`). Write them in English and ask for the output language
explicitly; `LanguagePrompt.systemInstruction` is the shared system instruction for that. When you change an extraction or figure
prompt, bump its `PROMPT_VERSION` so stored runs show which prompt produced them.

## Tests

- Unit and integration tests run against a real Postgres in Testcontainers
  (`pgvector/pgvector:pg16`), so Docker must be running. SAML is mocked in
  `TestcontainersConfiguration`, and the tests need no key pair.
- No automated test calls a model. `*LiveIT` classes run only with `RUN_LIVE_LOGOS=1` and a
  `LOGOS_API_KEY`; each class's Javadoc gives its command.
- Gradle caches test results. If a run finishes within seconds, add `--rerun-tasks`.

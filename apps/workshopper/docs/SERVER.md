# Server Architecture & Database Schema

The Workshopper server is a Spring Boot 3.5 application (Java 21, Gradle module `:apps:workshopper:server`). It provides the REST API for the client, runs the AI generation through the TUM AET Logos gateway (`libs:shared-llm`), and builds the PDF and PPTX exports. See [ARCHITECTURE.md](./ARCHITECTURE.md) for the layering rules and [API.md](./API.md) for the endpoints.

## Packages

```
server/src/main/java/com/workshopper/
├── controller/   WorkshopController (sessions, generation, export), CourseController (courses),
│                 ApiExceptionHandler (turns exceptions into 403/404/409/500 answers)
├── facade/       WorkshopSessionFacade: ownership checks, generation orchestration, course ZIP;
│                 UsageGuard: limits on AI requests, input sizes and stored data (see API.md)
├── usecase/      GenerateTimetableUseCase, GenerateSlideBlockUseCase, AssemblePptxUseCase,
│                 RefineLearningGoalUseCase, FixGoalsGrammarUseCase,
│                 ActivityRecommender, TemplateBranding, PptxTextFit
├── service/      LlmService (prompt rendering + LLM calls), WorkshopService (persistence,
│                 drafts, templates), PdfExportService (PDFBox)
├── config/       SecurityConfig (SAML, profile gate), DevAuthFilter (local profile), AuthContext
├── dto/          request/response records (JSON contract with the client)
├── model/        JPA entities: WorkshopSessionEntity, CourseEntity, SlideTemplateEntity
├── repository/   Spring Data repositories
├── exception/    SessionLockedException (409)
└── util/         FileNames: download names from session/course titles ("<Title> - Slides.pptx")

server/src/main/resources/
├── application.yml, application-local.yml, application-test.yml
├── prompts/*.st                        LLM prompt templates (see AI-INTEGRATION.md)
├── templates/workshopper-default.pptx  the default slide design (one template slide per layout)
└── db/migration/V1__…–V8__…            Flyway migrations
```

## Database Schema (PostgreSQL, Flyway V1–V8)

Migrations run on startup. Never edit a migration that has run anywhere; add a new `V<n>__description.sql` instead. Hibernate only validates the schema (`ddl-auto: validate`).

### `workshop_sessions`
| Column | Meaning |
|---|---|
| `id` | UUID |
| `owner_id` | the user who owns the session |
| `course_id` | FK to `courses` (`ON DELETE CASCADE`); null for standalone sessions |
| `display_order` | order on the dashboard |
| `title`, `student_background`, `duration`, `participants`, `session_type`, `session_type_other` | the settings from the Setup step |
| `draft_state_json` (JSONB) | the whole wizard state (`DraftState` in the client): settings, goals, kept goals, skeleton, timetable, tasks |
| `session_json` (JSONB) | the generated timetable (`WorkshopSessionDto`) |
| `slides_json` (JSONB) | generated or edited slides, by block index |
| `template_id` | FK to `slide_templates`: the session's own uploaded template |
| `use_default_template` | true if the session opted out of its course's template (V8) |
| `status` | `complete` once the session has a generated timetable (on any step from the timetable on), otherwise `draft`; only complete sessions are exported from the dashboard and in the course ZIP |
| `current_step` | the wizard step the session was last saved at |
| `created_at`, `updated_at` | timestamps |

Removed columns: `learning_goal` (V7), `interaction_level` (V6).

### `courses`
`id`, `owner_id`, `title`, `display_order`, `draft_state_json` (the course settings that new sessions inherit), `status`, `current_step`, `template_id` (FK to `slide_templates`, V5), `created_at`, `updated_at`.

### `slide_templates`
`id` (the session or course id it belongs to), `owner_id`, `file_name`, `file_data` (the .pptx), `created_at`.

## Slide templates

Which template applies to a session (`WorkshopService.getTemplate`):
1. the session's own upload, otherwise
2. its course's template, unless `use_default_template` is set, otherwise
3. the default design.

Removing a template in the slide workstation deletes the session's own upload and sets `use_default_template`; uploading a template clears it, and moving the session to another course resets it.

An uploaded template never replaces the default design. `AssemblePptxUseCase` always builds the deck from `workshopper-default.pptx`; `TemplateBranding` then takes the uploaded file's title slide, logo pictures and footer, found through its slide master, layouts and placeholder types, and scales them to the deck's page size.

## Tests

From the repository root:

```bash
./gradlew :apps:workshopper:server:test
```

`WorkshopSessionRepositoryTest` starts PostgreSQL with Testcontainers and needs Docker; the other tests run without a database (MockMvc, Mockito, plain unit tests).

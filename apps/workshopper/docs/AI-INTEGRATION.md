# AI Integration

Workshopper uses Spring AI (via the `libs:shared-llm` module) for the steps that need language: checking goals, writing the timetable content and writing slides. Everything structural is plain code: the session skeleton, which activity a goal gets, PPTX/PDF assembly and template branding.

## Where the LLM is called

All calls go through `LlmService.callWithStructuredOutput(systemTemplate, userTemplate, model, converter, useSecondary)`:
1. It loads `server/src/main/resources/prompts/<name>.st` and renders it with Spring AI's `PromptTemplate`, filling `{placeholders}` from the model map.
2. It sends the request, and parses the JSON answer into a DTO with `BeanOutputConverter`.

| Feature | Endpoint | Code | Prompt templates |
|---|---|---|---|
| Goal check (refine or split one goal) | `POST /refine-goal` | `RefineLearningGoalUseCase` | `refine-learning-goal-{system,user}.st` |
| Grammar fix for all goals | `POST /fix-goals-grammar` | `FixGoalsGrammarUseCase` | `fix-goals-grammar-{system,user}.st` |
| Timetable block content (one call per block, run in parallel) | `POST /session` | `GenerateTimetableUseCase` | `generate-timetable-block-{system,user}.st` |
| Session title | `POST /session` | `GenerateTimetableUseCase` | `generate-session-title-{system,user}.st` |
| Slides for one block | `POST /export/block-slides` | `GenerateSlideBlockUseCase` | `generate-slide-block-{welcome,activate,learning-cycle,evaluate,wrapup}-{system,user}.st` |

To change what the AI writes, edit the `.st` template. Every placeholder in a template must be in the model map built by the calling code; `LlmTemplatesTest` renders all templates and fails on a missing placeholder.

## Timetable generation

1. The client builds the session skeleton itself (`generateDefaultSkeleton` in `client/src/lib/workshop-generator.ts`): the sequence of blocks, which goal each covers, and their durations.
2. The server fills each block in one LLM call (`hydrateActivityBlock`). It sends:
   * the session settings, the goals (with Bloom's/SOLO level and prerequisites), the selected activities and the available materials;
   * for a learning cycle, the **required activity**, chosen by `ActivityRecommender` from the goal's Bloom's/SOLO level within the selected activities (falling back to lower levels; any activity if none are selected);
   * for Check Understanding, the activity-to-goal mapping, with one exact line to fill per mapped activity.
3. The server then checks the answer:
   * activity tags and materials the user didn't select are dropped (`restrictToSelections`);
   * a learning cycle gets exactly its required activity;
   * Check Understanding is rebuilt to one combined question per mapped activity (`normalizeEvaluateSteps`).

A second generation request for the same session while one is running is rejected with `409`.

## Slide generation

Slides are generated per timetable block. The phase decides the template: ARRIVE → welcome, ACTIVATE → activate, LEARNING_CYCLE → learning-cycle, EVALUATE → evaluate, SUMMARY → wrapup. The agenda slide (the goals, verbatim) and the break slides are built without the LLM. If an LLM call fails, the block gets fallback slides instead of an error.

Each slide is a map with a `layout` (which slide of the default template it uses, e.g. `activity_grid3`, `live_poll`, `debrief`) and a `group`. See the `SlideData` type in `workshop-generator.ts`.

## Not AI

* **PPTX assembly** (`AssemblePptxUseCase`): copies slides from `server/src/main/resources/templates/workshopper-default.pptx` and fills their placeholders.
  * The agenda slide gets one row per goal.
  * Text that doesn't fit is shrunk (`PptxTextFit`).
  * An uploaded template only contributes its title slide, logo and footer (`TemplateBranding`).
* **PDF export** (`PdfExportService`): rendered with Apache PDFBox.

## Configuration

* **Provider:** requests go to the TUM AET Logos gateway (the `shared-llm` default). The API key comes from `WORKSHOPPER_LOGOS_API_KEY`.
* **Secondary model:** `LLM_SECONDARY_MODEL` (property `llm.secondary-model`) optionally sets a second model, used for slide generation; it must be a Logos model id.
* **Temperature:** the JSON-returning calls use the shared default (temperature 0.0) so the output parses reliably.

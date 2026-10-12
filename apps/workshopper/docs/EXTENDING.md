# Extending Workshopper

Checklists for the most common changes. Each lists every place that has to change, so nothing is half-wired. After any change, run the checks at the end.

## Add or remove a wizard step

Steps are defined in `client/src/hooks/useWizardState.ts` and rendered in `client/src/App.tsx`.

1. **`useWizardState.ts`**
   * Add the id to the `Step` type and to `ALL_STEPS` (with its label, in display order).
   * Course-only step: add it to `COURSE_STEPS`. Session-only step: exclude it in `computeStepOrder` (and in `STEPS`) for courses.
   * Add a handler that saves and moves on, e.g. `persistDraft(buildDraft(...), "<next-step>", ...)` followed by `setStep("<next-step>")` (see `handleStep2`), and return it.
   * If the step's id is stored as `currentStep`, check `resumeSession`. It maps stored step names to the step a session reopens at (and `"result"`, `"finished"` are legacy names).
2. **`App.tsx`**: render the component with `{step === "<id>" && <YourStep ... />}`. Point the previous step's "Next" and the next step's "Back" at it.
   * If it is a settings form, add it to `FORM_STEPS` and keep its values in `workshopInput` while typing (like `WorkshopFormStep1`'s `onChange`).
3. **Unsaved input:** if the step has input that is only saved on a button, register a `LeaveGuard` (see `WorkshopGoalEntry` or `SlideWorkstation`).
4. **Dashboard:** add a label for the stored step name to `STEP_LABELS` in `SessionsDashboard.tsx`.
5. **Docs:** update the wizard lists in `README.md` and `docs/CLIENT.md`.

Removing a step is the reverse; also search for its id string (`grep -rn '"<id>"' client/src`), since saved drafts may still contain it as `currentStep`, and keep a mapping for it in `resumeSession`.

## Add a timetable block phase

Phases (`ARRIVE`, `ACTIVATE`, `LEARNING_CYCLE`, `BREAK`, `EVALUATE`, `SUMMARY`, `BUFFER`) are plain strings shared by client, server and prompts.

**Client:**
1. `lib/phases.ts`: add an entry to `PHASES`: label, emoji, colour family (`mode`), whether it is compact, expandable and gets slides, and a `template` if users can add it by hand (it then appears in the Add Block menu). The timetable, Final Review and slide workstation read this table.
2. `lib/workshop-generator.ts`: `generateDefaultSkeleton`, if the phase should be part of generated sessions.
3. Only if the expanded block needs its own layout: a case in `blockContent` (`lib/block-content.ts`, which picks the prompt and content out of the steps) and, for a new layout, in `components/timetable/BlockDetails.tsx`. Both the timetable and Final Review use these. Without a case, the timetable shows the block's steps with times and Final Review its objective.

**Server:**
1. `prompts/generate-timetable-block-system.st`: tell the model what the phase is for.
2. `usecase/GenerateSlideBlockUseCase.java`: the phase `switch` in `execute` decides its slides (or none, like `BUFFER`).
3. `service/PdfExportService.java`: its colour in the PDF.

## Add an activity

1. `client/src/lib/constants.ts`: add it to a group in `ACTIVITY_GROUPS` (name, emoji, description).
2. If it should be recommended for a Bloom's/SOLO level, add it to **both** `server/.../usecase/ActivityRecommender.java` and `client/src/lib/activity-taxonomy.ts`.
3. `server/.../usecase/GenerateSlideBlockUseCase.java`:
   * `activityLayout`: which slide layout it uses (default `activity_sidebar`);
   * `FIXED_INSTRUCTIONS`: the three steps shown on grid slides;
   * `getActivityName`: its label on the slide.
4. Update the tables in `docs/ACTIVITIES.md`.

## Change what the AI writes, or add an LLM call

* **Change a prompt:** edit the `.st` file in `server/src/main/resources/prompts/`. `docs/AI-INTEGRATION.md` lists which file serves which feature.
* **New placeholder:** add the value to the model map in the calling Java code, and add a dummy value in `LlmTemplatesTest`.
* **New LLM call:**
  1. Add `<name>-system.st` and `<name>-user.st`.
  2. Add a method in `LlmService` that calls `callWithStructuredOutput("<name>-system", "<name>-user", model, converter, useSecondary)`.
  3. Call it from a use case, never from a controller.

## Add a slide layout

1. Add a template slide to `server/src/main/resources/templates/workshopper-default.pptx` with `[Placeholder]` texts.
2. `AssemblePptxUseCase.getTemplateSlideIndex`: map the layout name to the slide index.
3. `AssemblePptxUseCase.populateSlideBody`: add a `case` that fills the placeholders.
4. Make the prompt or code that builds the slide produce that `layout` value.

## Add a field that is saved

* **Part of the wizard state only** (most cases): add it to the TypeScript types in `client/src/lib/workshop-generator.ts` (`WorkshopInput` or `DraftState`). It is saved inside `draft_state_json` automatically.
* **The server needs it:**
  1. Add it to the matching DTO record in `server/.../dto/`. Records are positional, so update every `new ...Dto(` call; `grep` for them.
  2. Follow it through facade → use case (see rule 2 in `AGENTS.md`).
* **It needs its own column:**
  1. Add a Flyway migration `V<next>__description.sql`. Never edit an existing one.
  2. Add the field to the JPA entity, and map it in `WorkshopService`.

## Add an endpoint

1. Add it to `WorkshopController` (or `CourseController`). Check ownership first: `facade.verifyOwnership(id)` or `facade.verifyCourseOwnership(id)`. Don't catch exceptions; `ApiExceptionHandler` turns them into the right status.
2. Put the logic in the facade (generation) or `WorkshopService` (persistence).
3. Add a function in `client/src/lib/api.ts` (built on its `get`/`post`/`postForBlob` helpers, which throw an `ApiError` for any failed answer), and document it in `docs/API.md`. Components never call `fetch` themselves; in a `catch`, call `handleAuthError(e)` first and show `errorMessage(e, fallback)` otherwise.
4. Add a MockMvc test in `server/src/test/.../controller/` (see `WorkshopControllerTest` and `WorkshopControllerSecurityTest`).

## Checks before committing

* **Server tests**, from the repository root (`WorkshopSessionRepositoryTest` needs Docker running):

  ```bash
  ./gradlew :apps:workshopper:server:test
  ```

* **Client type-check and build**, from `apps/workshopper/client`:

  ```bash
  npm run build
  ```

* **Manual check:** run the app locally (see `README.md`), click through the changed flow, and reload the page halfway through to check it resumes correctly.

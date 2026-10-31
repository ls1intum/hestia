# Extending LearningGoalHub

Checklists for changes that cross the server and the client. Each lists every place that has to
change, so nothing is half-wired. The basics of a single endpoint, LLM call or prompt are in
[server/README.md](../server/README.md#how-to-add-); start there if your change stays on the server.

To find the component behind a part of the UI, see [CLIENT.md](CLIENT.md). Server paths below
are relative to `server/src/main/java/de/tum/cit/hestia/learninggoalhub/`, client paths to
`client/src/`.

## Add a field to a learning goal

Example: a new attribute that is stored, shown in the Topic tree and editable, like `soloLevel`.

**Server**

1. A migration `server/src/main/resources/db/migration/V<next>__<name>.sql` that adds the column.
   Hibernate only validates the schema, so the server will not start without it. Find the highest
   number with `ls server/src/main/resources/db/migration | sort -V | tail -1`; a plain `ls` sorts
   `V9` after `V42`.
2. `goal/LearningGoal.java`: the field with its getter and setter.
3. `goal/LearningGoalResponse.java`: add it to the record and to `from(...)`. Records are positional,
   so the compiler shows every other place that builds one.
4. If it is editable: add it to `UpdateLearningGoalRequest` in `goal/LearningGoalController.java`
   and apply it in `LearningGoalEditService.update`. The request is a partial update, so a null
   means "unchanged", and a record field can therefore never be cleared again. If the field must be
   clearable, make the request tell "sent as null" from "left out", as `course/UpdateCourseRequest`
   does with `outputLanguageSet`. Check values in the service and throw `ResponseStatusException`
   with `BAD_REQUEST` and a readable message, as `LearningGoalEditService` does for blank text; the
   client shows that message.
5. If the CSV export should carry it: a column in `LearningGoalCsvWriter.HEADER` and the matching
   value in the row. Two tests spell out the full header and a full row and need the new column:
   `LearningGoalCsvWriterTest` and
   `LearningGoalControllerTest.exportCsvReturnsAllGoalsWithHierarchyAndSources`.
   Add the column to the list under `export.csv` in [api.md](api.md) too.
6. If extraction or the Topic tree should fill it, see
   [server/README.md](../server/README.md#an-llm-call) and bump the prompt's `PROMPT_VERSION`.

**Contract**

7. Update `client/openapi/openapi.json` and regenerate `client/src/api/schema.d.ts`
   ([client/README.md](../client/README.md#api-client), which explains why the snapshot is merged,
   not overwritten). `LearningGoal` in `api/client.ts` is derived from the schema and picks the
   field up.
8. ExamLens and Workshopper read `LearningGoalResponse`. A new field is additive and safe; document
   it under `LearningGoalResponse` in [api.md](api.md). Renaming or removing one breaks them.

**Client**

9. To send edits, add the field wherever an edit's fields are typed. They must agree, and
   TypeScript only complains where one passes into the next:
   - `GoalChanges` in `components/topicTree/model.ts` (the grid's rows and `usePendingEdits`);
   - the `onUpdate` prop of `components/TopicTree.tsx`;
   - `updateGoal` and the `updateGoalMutation` variables in `pages/CoursePage.tsx`.
10. To show it as a Topic tree column:
    - `components/topicTree/model.ts`: add the key to `AttributeKey` (and to `FilterKey` with a case
      in `valuesOf` and, for readable values, `displayValue` if the column filters).
    - `components/topicTree/columns.ts`: an entry in `COLUMNS`. Saved column layouts pick a new key
      up on their own.
    - `components/topicTree/GridRow.tsx`: the cell in the `cells` map. The map is cast
      (`as Record<AttributeKey, ReactElement>`), so a missing cell compiles: the header gets the
      column, and every row is one cell short, which shifts the cells after it into the wrong
      columns. A component test that compares each row's cell count with the headers catches it.
    - `components/topicTree/DiagramRows.tsx`: the diagram layout shows a box's attributes in
      `DiagramAttributes`, which picks them by hand (`columns.includes(...)`). Nothing fails if you
      skip it; the field is then missing from the diagram.
    - Decide which tiers show it. Topics have no Bloom or SOLO level: the table shows their level
      cells read-only and the diagram leaves them out (both test `row.role !== "topic"`). Do the
      same if the field means nothing for a topic.
    - For an inline editor, copy `RenameField` in `components/GoalInlineEditing.tsx`: it stops click
      and key events from reaching the row (the row opens on Enter, the grid closes on Escape), saves on
      Enter and on blur, cancels on Escape, and uses a `finished` ref so the blur that follows
      Escape does not save after all. For a pick from a fixed list, use `LevelCell`.
11. Add or extend a component test next to the component (`TopicTree.test.tsx`), and a browser test
    in `client/e2e/editing.e2e.ts` if the field is edited. If you cannot run the browser tests
    locally, say so in the pull request; the `test-e2e` CI job runs them on it.
12. Docs: the field under `LearningGoalResponse` in [api.md](api.md) (step 8), the CSV column
    list (step 5), and the table in [CLIENT.md](CLIENT.md) if you add a component.

## Add a course setting

Example: a switch chosen when the course is created, like `figuresEnabled`.

1. A migration that adds the column, with a default so existing courses stay valid.
2. `course/Course.java`: the field.
3. Create: `CreateCourseRequest` in `course/CourseController.java` and `CourseService.create`.
4. Change later: `course/UpdateCourseRequest.java` and `CourseService.update`.
5. Read: `CourseResponse` in `CourseController.java` and `course/CourseSummaryResponse.java` (built
   in `CourseService`).
6. If it changes extraction: read it in `extraction/ExtractionRunner.java` and add it to
   `runParams`, so each `extraction_run` row records the setting it ran with.
7. Regenerate the client types, then add the control to `components/CreateCourseDialog.tsx` and send
   it in the `POST /api/courses` body.

## Add an extraction phase

The progress bar and the phase list follow `ExtractionProgressTracker.Phase`.

1. `extraction/ExtractionProgressTracker.java`: add the value to `Phase` in pipeline order, and give
   it a share of the bar in `weightedPercent` (the `switch` does not compile without it). The shares
   must still run from 0 to 100.
2. `extraction/ExtractionRunner.java`: call `run.phase(Phase.<NEW>, total)` where the step starts
   and `run.increment()` as it completes work, like the existing phases. No model call inside a
   transaction ([conventions](../server/README.md#conventions)).
3. Regenerate the client types; the phase is an enum in the `Snapshot` schema.
4. `lib/extraction.ts`: an entry in `EXTRACTION_PHASES` with a `label` for the progress dialog and
   a `short` label that fits the courses list.
5. If the step sends a new prompt, teach `client/e2e/fake-llm.mjs` to answer it, or the browser
   tests fail with the fake's `400` (see [client/README.md](../client/README.md#browser-tests)).

## Add a page

1. A component in `pages/` and a `<Route>` in `App.tsx`. Paths are relative to the
   `/learninggoalhub` prefix, which `main.tsx` sets as the router's basename.
2. Fetch with the typed client from `api/client.ts` inside React Query (`useQuery`, `useMutation`).
   Do not call `fetch` directly. Turn a failed response into a message with
   `lib/requestError.ts`, which keeps the server's reason for a 4xx.
3. After a mutation, invalidate the query keys that show the changed data (`["courses"]`,
   `["goals", courseId]`, …); search for `invalidateQueries` to see which keys exist.
4. A component test (`*.test.tsx`, see [client/README.md](../client/README.md#scripts)) and, for a
   new user workflow, a browser test in `client/e2e/`.

## Checks before committing

From the repository root:

```bash
./gradlew :apps:learninggoalhub:server:test                               # needs Docker
(cd apps/learninggoalhub/client && npm run lint && npm run typecheck && npm test)
(cd apps/learninggoalhub/client && npm run e2e)                            # after a workflow change
```

CI runs the same three on every pull request that touches the app, and the images are built only
when they pass.

**Manual check.** Run the app locally ([README](../README.md#run-locally)), click through the flow
you changed in both Topic tree layouts (table and diagram), and reload the page halfway through.
Changes to extraction or a prompt need a real model: upload a small PDF to a new course and look
at the result, and keep `LOGOS_API_KEY` out of anything you commit or paste.

# Extending LearningGoalHub

Checklists for changes that cross the server and the client. Each lists every place that has to
change, so nothing is half-wired. The basics of a single endpoint, LLM call or prompt are in
[server/README.md](../server/README.md#how-to-add-); start there if your change stays on the server.

Server paths below are relative to `server/src/main/java/de/tum/cit/hestia/learninggoalhub/`,
client paths to `client/src/`.

## Add a field to a learning goal

Example: a new attribute that is stored, shown in the Topic tree and editable, like `soloLevel`.

**Server**

1. A migration `server/src/main/resources/db/migration/V<next>__<name>.sql` that adds the column.
   Hibernate only validates the schema, so the server will not start without it.
2. `goal/LearningGoal.java`: the field with its getter and setter.
3. `goal/LearningGoalResponse.java`: add it to the record and to `from(...)`. Records are positional,
   so the compiler shows every other place that builds one.
4. If it is editable: add it to `UpdateLearningGoalRequest` in `goal/LearningGoalController.java`
   and apply it in `LearningGoalEditService.update`. The request is a partial update, so a null
   means "unchanged".
5. If the CSV export should carry it: a column in `LearningGoalCsvWriter.HEADER` and the matching
   value in the row, and extend `LearningGoalCsvWriterTest`.
6. If extraction or the Topic tree should fill it, see
   [server/README.md](../server/README.md#an-llm-call) and bump the prompt's `PROMPT_VERSION`.

**Contract**

7. Regenerate `client/openapi/openapi.json` and `client/src/api/schema.d.ts`
   ([client/README.md](../client/README.md#api-client)). `LearningGoal` in `api/client.ts` is
   derived from the schema and picks the field up.
8. ExamLens and Workshopper read `LearningGoalResponse`. A new field is additive and safe; document
   it under `LearningGoalResponse` in [api.md](api.md). Renaming or removing one breaks them.

**Client**

9. To send edits: add the field to the `updateGoalMutation` variables in `pages/CoursePage.tsx`.
10. To show it as a Topic tree column:
    - `components/topicTree/model.ts`: add the key to `AttributeKey` (and to `FilterKey` with a case
      in `valuesOf` and, for readable values, `displayValue` if the column filters).
    - `components/topicTree/columns.ts`: an entry in `COLUMNS`. Saved column layouts pick a new key
      up on their own.
    - `components/topicTree/GridRow.tsx`: the cell in the `Record<AttributeKey, ReactElement>` map.
      TypeScript fails until it is there.
11. Add or extend a component test next to the component (`TopicTree.test.tsx`), and a browser test
    in `client/e2e/editing.e2e.ts` if the field is edited.

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

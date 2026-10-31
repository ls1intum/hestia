# Client

Where each part of the UI lives in `client/src/`, how it gets its data, and what to keep in mind
when changing it. Running, the API types and the browser tests are in
[client/README.md](../client/README.md); checklists for changes that reach the server are in
[EXTENDING.md](EXTENDING.md).

## From screen to code

The client has two pages, both under the `/learninggoalhub` prefix (`main.tsx` sets it as the
router's basename, `App.tsx` has the routes).

**Course list** (`/`, `pages/CoursesPage.tsx`)

| What you see | Component |
| --- | --- |
| the paged list of courses with their extraction status | `pages/CoursesPage.tsx` |
| "Create course": name, settings, PDF upload with lecture/exercise kind | `components/CreateCourseDialog.tsx` |
| a course row folded open: its documents, kinds and display names | `components/CourseDocuments.tsx` |
| "Analyzing course materials": progress, failure and retry | `components/ExtractionProgressModal.tsx`, `components/extractionProgress/RunProgress.tsx`, `RunFailure.tsx` |

**Course page** (`/courses/:courseId`, `pages/CoursePage.tsx`)

| What you see | Component |
| --- | --- |
| title, the ⋮ menu (Review, Export CSV, Delete), the goal update and delete requests | `pages/CoursePage.tsx` |
| the Topic tree grid, its toolbar and its two layouts | `components/TopicTree.tsx` |
| a row in the **table** layout (hierarchy in the first column) | `components/topicTree/GridRow.tsx` |
| a row in the **diagram** layout, and a topic's map opened under it | `components/topicTree/DiagramRows.tsx`, `components/TopicMap.tsx`, `components/topicMap/` |
| columns: which exist, their order and width (kept in local storage) | `components/topicTree/columns.ts`, `useColumnLayout.ts`, `Toolbar.tsx`, `HeaderCell.tsx` |
| filters and the search, with their chips | `components/topicTree/useGoalFilters.ts`, `FilterChips.tsx`, `components/FilterPopover.tsx` |
| Bloom and SOLO cells with their level picker | `components/topicTree/LevelCell.tsx` |
| tier, kind, source and coverage chips | `components/topicTree/Chips.tsx`, `components/CoverageBadge.tsx`, `components/GoalBadges.tsx` |
| inline rename and the row's hover actions | `components/GoalInlineEditing.tsx` |
| adding a topic (by hand or "Generate with AI") or a child under a row | `components/topicTree/useGoalCreation.ts`, `components/TreeCreationField.tsx`, `lib/createTopic.ts` |
| "Find in the slides" | `components/TopicSearchDialog.tsx`, `lib/topicSearch.ts` |
| the PDF beside the grid with the highlighted quote | `components/topicTree/SourcePanel.tsx`, `components/SourcePdfPane.tsx` |
| the explanation of the four tiers | `components/topicTree/TierGuide.tsx`, `useTierGuide.ts` |
| the skill review after a run (accept, rename, dismiss topics and skills) | `components/extractionProgress/SkillReview.tsx`, `ReviewRows.tsx`, `review.ts` |

Shared pieces: `components/Layout.tsx` (top bar), `Button.tsx`, `ConfirmDialog.tsx`,
`AnchoredPopover.tsx`, `InfoTooltip.tsx`, `ErrorBoundary.tsx`, and `lib/useFocusTrap.ts` for
modals.

## Tier names

The UI's four tiers are Topic → Skill → Sub-skill → Knowledge. The API marks them differently: a
topic is `origin: TERMINAL`, both skills and sub-skills are `role: SKILL` and only their position
in the tree tells them apart, knowledge is `role: KNOWLEDGE`
([api.md](api.md#topic-tree)). The client follows the UI: `buildTopicForest` in `lib/goals.ts`
builds the tree and gives every node a `TreeRole` (`topic`, `skill`, `subSkill`, `knowledge`) from
its position, and `TREE_ROLE_META` and `tierNoun` name it. Use a row's `role` (a `TreeRole`), not
the goal's API `role`. On the server, the Skill tier is called "capability" and the Sub-skill
tier "skill".

## Data

All server data goes through TanStack Query with the typed client from `api/client.ts`
(`openapi-fetch` over the generated `api/schema.d.ts`). No component calls `fetch` itself; the
exceptions are the CSV export, a plain link, and the PDF, which pdf.js loads from its URL.

| Query key | Holds | Read by |
| --- | --- | --- |
| `["courses"]` (and `["courses", page, size]`) | the course list | `CoursesPage` |
| `["course", courseId]` | one course, its counts and settings | `CoursePage` |
| `["goals", courseId]` | every goal of the course, all pages (`lib/fetchGoals.ts`) | `CoursePage`, `TopicTree` |
| `["documents", courseId]` | the course's documents | `CourseDocuments`, `SourcePdfPane` |
| `["extract-status", courseId]` | the running or last extraction | `CoursePage`, `CoursesPage` (passed to `ExtractionProgressModal`) |
| `["extraction-current"]` | the extraction running anywhere on the server | `CoursesPage` |
| `["topic-search-pages", courseId, topic]` | pages found for "Find in the slides" | `TopicSearchDialog` |

After a write, invalidate every key whose data changed. Deleting a goal, for example, invalidates
`["goals", courseId]`, `["course", courseId]` and `["courses"]`, because the course row shows a goal
count.

The server sends an unset field as `null`, although the generated types mark it as optional
(`field?: X`). Compare with `??` or `== null`, not `=== undefined`.

A failed write throws `requestError(...)` (`lib/requestError.ts`). It keeps the server's
`{code, message}` reason for a 4xx, which names the field or the conflict, so show its message.

**Edits in the grid** appear at once. `topicTree/usePendingEdits.ts` lays them over the fetched
goals until the next fetch replaces the list, and takes an edit back off if its save fails.
`CoursePage.updateGoal` sends them through `updateGoalMutation`. The fields an edit can carry are
typed in four places, which have to agree: `GoalChanges` in `topicTree/model.ts`, the `onUpdate`
prop of `TopicTree`, and `updateGoal` and the `updateGoalMutation` variables in `CoursePage`.

## Styling

Tailwind CSS v4, configured in `index.css`. Colours are CSS variables (`--hestia-*`, light and dark
values side by side), exposed to Tailwind as `hestia-*` colours through `@theme inline`. Use those
tokens (`bg-hestia-surface`, `text-hestia-primary`, …) instead of fixed colours or `dark:`
variants, so a new element follows the theme toggle. `theme/` holds the light/dark/system switch.

## Tests

- Helpers in `lib/` have Node tests next to them (`goals.test.ts`, `fetchGoals.test.ts`,
  `requestError.test.ts`).
- Component tests (`*.test.tsx`: `TopicTree`, `CoursePage`, `ConfirmDialog`, `RenameCourseDialog`,
  `ChildPreview`) run in jsdom and mock `api/client.ts`. Start from `TopicTree.test.tsx` for a
  change to the grid.
- Browser tests in `client/e2e/` drive the real server against a fake model:
  `course.e2e.ts` (create, extract, review, CSV, delete), `editing.e2e.ts` (edits and the source pane),
  `failures.e2e.ts` (what the user sees when something fails) and `accessibility.e2e.ts` (axe).

```bash
cd apps/learninggoalhub/client
npm run lint && npm run typecheck && npm test
npm run e2e        # needs Postgres on 5433 and the built jar, see client/README.md
```

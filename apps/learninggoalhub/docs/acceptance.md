# Acceptance pass

A manual pass over every requirement of the system, run in the browser against a fresh database
with the production model configuration (Logos: `openai/gpt-oss-120b` for text,
`Qwen/Qwen3.8-27B` for vision). It records what was exercised, what held, and what did not.
Repeat it before a release and add a dated section. The automated browser tests
(`client/e2e/`, see [client/README.md](../client/README.md#browser-tests)) cover the core
workflows on every pull request; this pass covers what they cannot: real model output, the
stretch features, and visual checks.

## 2026-10-26

**Setup.** Server jar from `main` (Flyway V42) on an empty Postgres, client on the Vite dev
server, driven with Playwright scripts plus inspection of the screenshots. Material: a synthetic
algorithms course written for this pass, so it has no third-party rights: one PDF with two
bookmarked lectures (sorting, searching and hashing, 5 pages, one page a merge-sort diagram), one
bookmark-less lecture on graphs (2 pages), one exercise sheet. "Analyse slide images" was on.

**Result.** 17 of 19 functional requirements behave as specified (three with content caveats),
FR9 works with a quality caveat, and the stretch goal FR7 fails. Findings F1–F9 are listed after the tables.

### Functional requirements

| FR | Requirement | What was done | Result |
| --- | --- | --- | --- |
| 1 | Manage Course | created, renamed (reload keeps the name), deleted (browser tests); list shows documents, topics, skills | ✅ |
| 2 | Upload Material | two lectures and one exercise sheet in their own upload areas | ✅ |
| 3 | Detect Sessions | the bookmarked PDF became "Lecture 1: Sorting" and "Lecture 2: Searching"; the bookmark-less PDF one session | ✅ |
| 4 | Extract Learning Goals | 24 goals with knowledge under each sub-skill, in 72 s for the whole run | ✅ caveat F5 |
| 5 | Attribute Source | every extracted goal has a text source (24/24) | ✅ |
| 6 | Classify Goals | every extracted goal has Bloom and SOLO; hand-made goals stay unclassified by design | ✅ |
| 7 | Describe Figures (stretch) | the diagram page was sent to the vision model | ❌ F1 |
| 8 | Report Progress | phases and percentage shown in the list; a failed run names its sessions (browser test) | ✅ |
| 9 | Build Topic Tree | tree built, but one topic for the whole course and no skill tier in it | ⚠️ F6 |
| 10 | Place Exercises (stretch) | the exercise sub-skill sits under the lecture topic with an "Exercise" coverage badge | ✅ |
| 11 | Rebuild Topic Tree | rebuild after edits refused with 409, naming 23 hand-made or approved nodes | ✅ |
| 12 | View Topic Tree | table and diagram, with tier, coverage, kind, levels and source | ✅ |
| 13 | Inspect Source | the source pane opens the right page and session with the quoted line highlighted | ✅ |
| 14 | Edit Learning Goals | rename, SOLO change, delete, add by hand; all survive a reload; "Generate with AI" added a topic with 22 goals in 30 s | ✅ caveat F7 |
| 15 | Approve Learning Goals | review on first open; accept all, rename and dismiss; counts persist | ✅ |
| 16 | Find Missing Topic (stretch) | for "Hashing" the dialog proposed exactly the hashing page; the extraction found nothing new there and said so | ✅ caveat F5, F8 |
| 17 | Export Learning Goals | CSV with header and one row per goal (26 rows) | ✅ |
| 18 | Provide Learning Goals | `status=APPROVED` returns only the 5 accepted goals, `by-session` groups them; `/v3/api-docs` served | ✅ |
| 19 | Generate Exam Goals (stretch) | two exam tasks gave 3 goals in 10 s, placed in the tree and shown there | ✅ |

### Quality attributes

| QA | Observation |
| --- | --- |
| 1 Usability | every step above was reachable from the visible UI without documentation |
| 2 Traceability | every extracted goal carried a source; the highlighted quote matched the page text |
| 3 Repeatability | not measured in this pass (one run); covered by the thesis evaluation |
| 4 Fault Tolerance | a failed figure call degraded to "no figure source" and the run continued; a failed run saves nothing (browser test) |
| 5 Inference Cost | 10 model calls for the run on 3 documents (5 extraction including one retry, 4 tree, 1 vision) |
| 6–8 | model names and prompt versions come from configuration and are recorded per run (inspection, not exercised) |

### Trying to break it

- Double-clicking "Create course" created one course.
- Reloading the page during an extraction picked the progress up again and finished normally.
- A failed upload, a rejected save and a failed model call are covered by the browser tests,
  each with a visible message and no partial data.
- With the model gateway unreachable, an upload hangs: see F2.

### Visual checks

Course list, table, diagram and review dialog were captured in the light and dark theme, each
with no filter, greyscale (achromatopsia) and simulated deuteranopia and protanopia (Chrome's
vision-deficiency emulation). In the table, every state that colour marks also has a text label
(tier, coverage, kind) or a pip pattern (Bloom, SOLO), so it reads the same in greyscale and
without red–green distinction. All screens stayed legible under every filter. An axe-core WCAG 2 A/AA scan of the same screens found no issues in the course
list, and the issues listed under F3 and F4 elsewhere.

### Findings

| # | Severity | Finding |
| --- | --- | --- |
| F1 | high (stretch) | Figure description never succeeds: the vision model answers a single object `{"description": …}` where `PageDescriptionService` expects a list, so binding fails (`MismatchedInputException`) and the page gets no description. |
| F2 | medium | With the gateway unreachable, uploading a PDF whose bookmarks need the model's verdict blocks for minutes at "Uploading… 60%": `BookmarkRelevanceJudge` runs inside the upload request with a 20 s connect timeout and 8 retries before it falls back to splitting. |
| F3 | medium (a11y) | The Topic tree is a `role="table"` with expandable rows. axe reports `aria-required-children` (a row group holds elements that are not rows) and `aria-conditional-attr` (row attributes that ARIA allows only in a treegrid), in both themes, on the table, diagram and review screens. |
| F4 | low (a11y) | Light theme: the session line in a source chip (`opacity-75` on muted text) reaches 3.4:1, below 4.5:1. |
| F5 | content | Thin pages yield no sub-skill: merge sort, hashing and BFS/DFS appear only as knowledge items or not at all, because a short unit is allowed one skill. Expected from the density setting; it shows when a course has little text per lecture. |
| F6 | content | The planner named two topics for three skills but assigned all three to one, so topic 1 has no skill tier and shows its sub-skills under "Additional sub-skills". |
| F7 | content | "Generate with AI" mixes imperatives ("Explain the principles of …") with the gerund wording of extracted goals, and returns one skill at UNDERSTAND. |
| F8 | cosmetic | "Read 1 pages" in the Find-in-the-slides dialog. |
| F9 | dev only | After deleting a course the client navigates to `/learninggoalhub` without a trailing slash; reloading that URL on the Vite dev server shows Vite's base-URL hint instead of the app. |

**Fixed since.** F1: a bare object now binds where a list was asked for; a rerun stored the
diagram's description. F2: the judge gives up after `hestia.bookmark.judge-timeout` (30 s); with
the gateway unreachable the same upload finished in 35 s. F3: the tree is a treegrid, every child
of a row group is a row, and a diagram box unfolds through its count line rather than being a
button around buttons. The axe test that came with it also found that the PDF pane could not be
scrolled from the keyboard; fixed too. F4: the secondary chip lines no longer dim muted text
twice (5.4:1). `client/e2e/accessibility.e2e.ts` scans these screens in both themes on every pull
request.

### Backup restore

`scripts/verify-backup.sh` restored a dump of this pass's database (2 courses, 60 goals, an exam
submission) and a dump of the browser-test database (38 courses, 702 goals) into a disposable
Postgres, started the published server image (`…/learninggoalhub/server:latest`) on each copy,
and both served all courses. A truncated dump failed the check with a `pg_restore` error. The
dumps on the deployment VM have not been checked yet; see [DEPLOY.md](../DEPLOY.md#check-that-a-dump-restores).

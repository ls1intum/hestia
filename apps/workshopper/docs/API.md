# Workshopper API

All endpoints live under `/api/workshop` and need a logged-in user (see [AUTH.md](AUTH.md)); the only exception is `GET /api/workshop/health`.

Common responses:
* `401` not logged in (the client redirects to the SAML login)
* `403` the session or course belongs to someone else
* `404` unknown session or course
* `409` a timetable generation for the same session is already running
* `500` an unexpected server error, with `{"error": "..."}`; the details are only in the server log

All of these come from `ApiExceptionHandler`, so the controllers themselves have no error handling.

Request and response bodies are the DTOs in `server/src/main/java/com/workshopper/dto`; their TypeScript counterparts are in `client/src/lib/workshop-generator.ts`.

## Generation

| Method | Path | Body | Purpose |
|---|---|---|---|
| POST | `/session` | `GenerateSessionRequestDto` (goals, meta, availableMaterials, skeleton) | Generates the timetable, or one block of it when the skeleton has one block (used when switching or adding an activity). `skeleton.sessionId` is the draft to save it to. Returns `WorkshopSessionDto`. |
| POST | `/refine-goal` | `RefineGoalRequestDto` (goal, context) | AI check of one learning goal. Returns `GoalSuggestionDto[]` (`refine` or `split`); empty if the goal looks fine. |
| POST | `/fix-goals-grammar` | `string[]` | Fixes the grammar of the goals; returns the original goals if the LLM call fails. |
| POST | `/export/block-slides` | `BlockSlidesRequestDto` (block, meta, goals) | Generates the slides of one timetable block (JSON, not a file). |

## Export

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/export/pdf` | `PdfExportRequestDto` (session, meta, goals) | Timetable as PDF (rendered with PDFBox, no LLM). |
| POST | `/export/pptx` | `PdfExportRequestDto` | Slide deck as PPTX, from the slides stored in the session. |
| POST | `/export/pptx-assemble` | `PptxAssembleRequestDto` (session, meta, prebuiltSlides) | Slide deck assembled from slides the client already has. |
| POST | `/export/pptx-with-template` | multipart: `session`, `meta` (JSON strings), optional `slides`, optional `template` file | Slide deck; without a `template` file the session's stored template is used. |
| GET | `/export/course/{id}/zip` | – | ZIP with the PDF and PPTX of every session in a course. |

All PPTX exports use the default Workshopper design, branded with the session's effective template if there is one (its own upload, else its course's, unless the session opted out). See [SERVER.md](SERVER.md#slide-templates).

## Sessions

| Method | Path | Body | Purpose |
|---|---|---|---|
| GET | `/sessions` | – | The user's sessions (`SessionSummaryDto[]`, without timetables). |
| GET | `/sessions/{id}` | – | One session with its draft state, timetable and template info (`SessionDetailDto`). |
| POST | `/sessions/draft` | `SaveDraftRequestDto` (sessionId, currentStep, title, draftStateJson, courseId) | Creates or updates a session draft; returns `{ "id": ... }`. A `courseId` must be one of the user's courses. |
| POST | `/sessions/{id}/slides` | `{ [blockIndex]: slide[] }` | Stores the generated or edited slides. |
| PUT | `/sessions/{id}/finish` | – | Marks the session as finished. |
| PUT | `/sessions/{id}/rename` | `{ "title": ... }` | Renames the session. |
| PUT | `/sessions/{id}/move` | `{ "courseId": ... }` | Moves the session into a course; an empty `courseId` moves it back to the top level. |
| PUT | `/sessions/reorder` | `string[]` (session ids in order) | Saves the order of sessions within one level. |
| DELETE | `/sessions/{id}` | – | Deletes the session and its own template. |
| POST | `/sessions/{id}/template` | multipart `template` (.pptx) | Uploads the session's own slide template. |
| DELETE | `/sessions/{id}/template` | – | Removes the template from this session only: deletes its own upload and opts out of the course template. |

## Courses

| Method | Path | Body | Purpose |
|---|---|---|---|
| GET | `/courses` | – | The user's courses (`CourseSummaryDto[]`). |
| GET | `/courses/{id}` | – | One course with its settings (`draftStateJson`) and template info. |
| POST | `/courses/draft` | `SaveDraftRequestDto` (sessionId = course id) | Creates or updates a course; fields that are not sent are left unchanged. |
| PUT | `/courses/{id}/rename` | `{ "title": ... }` | Renames the course. |
| DELETE | `/courses/{id}` | – | Deletes the course, its sessions and their templates. |
| POST | `/courses/{id}/template` | multipart `template` (.pptx) | Uploads the course's slide template. |
| DELETE | `/courses/{id}/template` | – | Removes the course's template. |

## Other

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Returns `OK`; needs no login. |

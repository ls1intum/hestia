# Workshopper API Documentation

The Workshopper backend provides a set of REST endpoints under the `/api/workshop` base path. These endpoints handle the AI-driven workshop generation process, session management, and document export.

## Workshop Generation Flow

The workshop generation is a multi-step process:

### 1. `POST /api/workshop/plan`
Generates the initial learning goal plans based on user input.
- **Request Body**: `WorkshopInputDto` (Target audience, context, topic, etc.)
- **Response**: `List<LearningGoalPlanDto>`

### 2. `POST /api/workshop/activities`
Takes the refined learning goals and generates teaching and assessment activities for each.
- **Request Body**: `GenerateActivitiesRequestDto` (Selected goals, metadata, available materials)
- **Response**: `List<LearningGoalPlanDto>` (Updated with activities)

### 3. `POST /api/workshop/session`
Generates the full, finalized timetable session from the goals and activities.
- **Request Body**: `GenerateSessionRequestDto` (Goals, metadata, session skeleton)
- **Response**: `WorkshopSessionDto` (The complete timetable)

## Session Management

### `POST /api/workshop/sessions/draft`
Saves the current state of a workshop generation in progress.
- **Request Body**: `SaveDraftRequestDto`
- **Response**: `{"id": "<session-id>"}`

### `GET /api/workshop/sessions/{id}`
Retrieves a full session detail (including draft state if not finished).
- **Response**: Session JSON payload.

### `GET /api/workshop/sessions`
Lists all sessions as lightweight summaries.
- **Response**: `List<SessionSummaryDto>`

### `PUT /api/workshop/sessions/{id}/finish`
Marks a draft session as fully finished and saved.

### `PUT /api/workshop/sessions/{id}/rename`
Renames an existing session.

### `DELETE /api/workshop/sessions/{id}`
Deletes a session.

### `PUT /api/workshop/sessions/reorder`
Updates the display order of sessions.

### `PUT /api/workshop/sessions/{id}/move`
Moves a session into a specific lecture group.

## Export Endpoints

### `POST /api/workshop/export/pdf`
Exports a generated session to a highly-formatted PDF document using an LLM to generate LaTeX/Markdown.
- **Response**: `application/pdf`

### `POST /api/workshop/export/pptx`
Exports a session to a PowerPoint presentation.
- **Response**: `application/vnd.openxmlformats-officedocument.presentationml.presentation`

### `POST /api/workshop/export/pptx-assemble`
Assembles a PPTX from pre-built/cached slides without calling the LLM.

### `POST /api/workshop/export/pptx-with-template` (Multipart)
Upload a custom PPTX template, and export the session using the template.

### `POST /api/workshop/export/block-slides`
Generates JSON representation of slides for a single timetable block (used for frontend preview).

## Utility Endpoints

### `POST /api/workshop/refine-goal`
Provides real-time feedback and suggestions on a specific learning goal string.

### `POST /api/workshop/extract-goals`
Extracts raw learning goals from uploaded PDF materials.

### `POST /api/workshop/fix-goals-grammar`
Automatically fixes grammar and typos in learning goals.

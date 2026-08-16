# AI/LLM Prompts & Integration Guide

The Workshopper backend leverages Spring AI to orchestrate LLM prompts that convert user metadata into structured workshop sessions.

## Multi-Stage Prompt Pipeline

To ensure high-quality output, the generation is split into multiple distinct LLM calls rather than one monolithic prompt. This reduces hallucinations and enforces a strict sequence of thought.

### 1. Learning Goals Generation (`/plan`)
**Goal**: Convert unstructured context and topic into discrete, measurable learning goals.
- **Input**: Topic, Target Audience, Prior Knowledge, Context, Optional PDF text.
- **Output**: A JSON array of `LearningGoalPlanDto` objects.
- **System Prompt Focus**: Enforces Bloom's Taxonomy, making sure verbs are observable and actionable.

### 2. Activity Generation (`/activities`)
**Goal**: Attach specific teaching phases and practical activities to each learning goal.
- **Input**: The approved learning goals from Step 1, plus preferred activity types (e.g., Think-Pair-Share, Group Discussion).
- **Output**: Updated `LearningGoalPlanDto` objects containing the nested activities.
- **System Prompt Focus**: Enforces realistic time constraints and ensures the selected activities match the cognitive level of the goal.

### 3. Session Timetable Generation (`/session`)
**Goal**: Organize the goals and activities into a coherent chronological timetable.
- **Input**: The goals with attached activities.
- **Output**: A `WorkshopSessionDto` containing a list of `TimetableBlockDto` objects.
- **System Prompt Focus**: Managing time logic, ensuring smooth transitions between blocks, and generating introductory and concluding blocks to frame the session.

### 4. Slide Generation (`/export/block-slides`)
**Goal**: Generate presentation slide content for each block in the timetable.
- **Input**: A single timetable block and its associated activities.
- **Output**: A JSON array of slide objects (title, bullet points, presenter notes).
- **System Prompt Focus**: Condensing complex activity instructions into readable slide formats.

## Configuration

LLM connections are configured via the `libs:shared-llm` module and `application.yml`.
The API Key is provided via the `WORKSHOPPER_SAIA_API_KEY` environment variable.

The models are usually configured for high structure (temperature = 0.0) for the JSON-returning endpoints to ensure reliable parsing by Jackson.

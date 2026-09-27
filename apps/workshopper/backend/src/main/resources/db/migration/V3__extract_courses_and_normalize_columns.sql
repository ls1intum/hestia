-- V3: Extract courses table, convert TEXT→JSONB, promote scalars, remove dead prerequisites column

-- 1. Create courses table (formerly "Lecture" rows in workshop_sessions)
CREATE TABLE courses (
    id VARCHAR(255) PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    title TEXT,
    display_order INTEGER,
    draft_state_json JSONB,
    status VARCHAR(20),
    current_step VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 2. Migrate existing LECTURE rows into courses
INSERT INTO courses (id, owner_id, title, display_order, draft_state_json, status, current_step, created_at, updated_at)
SELECT id, owner_id, title, display_order,
       CASE WHEN draft_state_json IS NOT NULL AND draft_state_json != '' THEN draft_state_json::jsonb ELSE NULL END,
       status, current_step, created_at, updated_at
FROM workshop_sessions WHERE type = 'LECTURE';

-- 3. Remove LECTURE rows from workshop_sessions
DELETE FROM workshop_sessions WHERE type = 'LECTURE';

-- 4. Drop type column (no longer needed — all remaining rows are sessions)
ALTER TABLE workshop_sessions DROP COLUMN type;

-- 5. Rename lecture_id → course_id and add FK with cascade delete
ALTER TABLE workshop_sessions RENAME COLUMN lecture_id TO course_id;
ALTER TABLE workshop_sessions
    ADD CONSTRAINT fk_workshop_sessions_course
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE;

-- 6. Convert TEXT columns to JSONB (NULL-safe)
ALTER TABLE workshop_sessions
    ALTER COLUMN session_json TYPE JSONB
    USING CASE WHEN session_json IS NOT NULL AND session_json != '' THEN session_json::jsonb ELSE NULL END;

ALTER TABLE workshop_sessions
    ALTER COLUMN draft_state_json TYPE JSONB
    USING CASE WHEN draft_state_json IS NOT NULL AND draft_state_json != '' THEN draft_state_json::jsonb ELSE NULL END;

ALTER TABLE workshop_sessions
    ALTER COLUMN slides_json TYPE JSONB
    USING CASE WHEN slides_json IS NOT NULL AND slides_json != '' THEN slides_json::jsonb ELSE NULL END;

-- 7. Promote WorkshopInput scalars to real columns
ALTER TABLE workshop_sessions ADD COLUMN duration INTEGER;
ALTER TABLE workshop_sessions ADD COLUMN participants INTEGER;
ALTER TABLE workshop_sessions ADD COLUMN session_type VARCHAR(50);
ALTER TABLE workshop_sessions ADD COLUMN session_type_other VARCHAR(100);
ALTER TABLE workshop_sessions ADD COLUMN interaction_level VARCHAR(50);

-- 8. Backfill scalar values from existing draft_state_json blobs
--    (load-bearing: without this, sessions created before this migration lose these values)
UPDATE workshop_sessions SET
    duration           = (draft_state_json -> 'workshopInput' ->> 'duration')::int,
    participants       = (draft_state_json -> 'workshopInput' ->> 'participants')::int,
    session_type       = draft_state_json -> 'workshopInput' ->> 'sessionType',
    session_type_other = draft_state_json -> 'workshopInput' ->> 'sessionTypeOther',
    interaction_level  = draft_state_json -> \'workshopInput\' ->> \'interactionLevel\'
WHERE draft_state_json IS NOT NULL
  AND draft_state_json -> 'workshopInput' IS NOT NULL;

-- 9. Drop the dead session-level prerequisites column
--    NOTE: Do NOT confuse with LearningGoalPlanDto.prerequisites (per-goal field, untouched).
ALTER TABLE workshop_sessions DROP COLUMN prerequisites;

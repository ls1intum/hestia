-- The exam an exam goal was derived from. Each request to the exam-goal endpoint is one submission
-- holding its blocks in exam order; a task block keeps the context the model saw alongside it, and
-- every exam goal points at the task block it came from. Goals created before V41 have no block:
-- the endpoint stored nothing but the goal text then. No backfill.
CREATE TABLE exam_submission (
    id         BIGSERIAL PRIMARY KEY,
    course_id  BIGINT      NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_exam_submission_course ON exam_submission (course_id);

CREATE TABLE exam_block (
    id            BIGSERIAL PRIMARY KEY,
    submission_id BIGINT      NOT NULL REFERENCES exam_submission (id) ON DELETE CASCADE,
    position      INT         NOT NULL,
    block_id      TEXT,
    block_type    VARCHAR(16) NOT NULL CHECK (block_type IN ('CONTEXT', 'TASK')),
    task_type     TEXT,
    task_number   INT,
    description   TEXT,
    context       TEXT,
    CONSTRAINT exam_block_position_unique UNIQUE (submission_id, position)
);

ALTER TABLE learning_goal
    ADD COLUMN exam_block_id BIGINT REFERENCES exam_block (id) ON DELETE SET NULL;

CREATE INDEX idx_learning_goal_exam_block ON learning_goal (exam_block_id);

CREATE TABLE learning_goal (
    id          BIGSERIAL PRIMARY KEY,
    course_id   BIGINT       NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    text        TEXT         NOT NULL,
    kind        VARCHAR(16)  NOT NULL CHECK (kind IN ('EXPLICIT', 'IMPLICIT')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_learning_goal_course ON learning_goal (course_id);

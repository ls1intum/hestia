-- Goal candidates are the fine-grained, per-chunk output of the first extraction stage. The second
-- stage (SessionGoalConsolidator) merges each session's candidates into a handful of broad
-- learning_goal rows; we keep the raw candidates here for traceability — consolidated_goal_id records
-- which consolidated goal each candidate was merged into (NULL until the goal is persisted, or when a
-- candidate was dropped during consolidation). Candidates hang off their session's hierarchy node, so
-- pruning an empty session removes its candidates too.
CREATE TABLE goal_candidate (
    id                   BIGSERIAL PRIMARY KEY,
    course_id            BIGINT       NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    hierarchy_node_id    BIGINT       NOT NULL REFERENCES hierarchy_node (id) ON DELETE CASCADE,
    consolidated_goal_id BIGINT       REFERENCES learning_goal (id) ON DELETE SET NULL,
    text                 TEXT         NOT NULL,
    kind                 VARCHAR(16)  NOT NULL CHECK (kind IN ('EXPLICIT', 'IMPLICIT')),
    source_snippet       TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_goal_candidate_course ON goal_candidate (course_id);
CREATE INDEX idx_goal_candidate_node ON goal_candidate (hierarchy_node_id);
CREATE INDEX idx_goal_candidate_goal ON goal_candidate (consolidated_goal_id);

CREATE TABLE hierarchy_node (
    id        BIGSERIAL PRIMARY KEY,
    parent_id BIGINT       REFERENCES hierarchy_node (id) ON DELETE CASCADE,
    course_id BIGINT       NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    level     VARCHAR(16)  NOT NULL CHECK (level IN ('MODULE', 'SESSION', 'EXERCISE')),
    label     TEXT         NOT NULL
);

CREATE INDEX idx_hierarchy_node_parent ON hierarchy_node (parent_id);
CREATE INDEX idx_hierarchy_node_course ON hierarchy_node (course_id);

ALTER TABLE learning_goal
    ADD COLUMN hierarchy_node_id BIGINT REFERENCES hierarchy_node (id) ON DELETE SET NULL;

CREATE INDEX idx_learning_goal_hierarchy ON learning_goal (hierarchy_node_id);

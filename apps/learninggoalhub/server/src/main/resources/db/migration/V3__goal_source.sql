CREATE TABLE goal_source (
    goal_id     BIGINT NOT NULL REFERENCES learning_goal (id) ON DELETE CASCADE,
    document_id BIGINT NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    snippet     TEXT   NOT NULL,
    PRIMARY KEY (goal_id, document_id)
);

CREATE INDEX idx_goal_source_document ON goal_source (document_id);

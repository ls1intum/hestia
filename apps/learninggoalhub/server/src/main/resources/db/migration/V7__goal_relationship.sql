CREATE TABLE goal_relationship (
    id               BIGSERIAL PRIMARY KEY,
    source_goal_id   BIGINT           NOT NULL REFERENCES learning_goal (id) ON DELETE CASCADE,
    target_goal_id   BIGINT           NOT NULL REFERENCES learning_goal (id) ON DELETE CASCADE,
    type             VARCHAR(32)      NOT NULL
        CHECK (type IN ('CONTRIBUTES_TO', 'PREREQUISITE_OF', 'OVERLAPS_WITH')),
    confidence       DOUBLE PRECISION NOT NULL,
    origin           VARCHAR(32)      NOT NULL
        CHECK (origin IN ('HIERARCHY', 'EMBEDDING', 'LLM')),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT goal_relationship_no_self_loop CHECK (source_goal_id <> target_goal_id),
    CONSTRAINT goal_relationship_unique UNIQUE (source_goal_id, target_goal_id, type)
);

CREATE INDEX idx_goal_relationship_source ON goal_relationship (source_goal_id);
CREATE INDEX idx_goal_relationship_target ON goal_relationship (target_goal_id);

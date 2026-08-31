ALTER TABLE goal_relationship
    DROP CONSTRAINT goal_relationship_origin_check;

ALTER TABLE goal_relationship
    ADD CONSTRAINT goal_relationship_origin_check
        CHECK (origin IN ('HIERARCHY', 'EMBEDDING', 'LLM', 'SYNTHESIS'));

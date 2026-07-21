-- Distinguishes goals an instructor added after extraction (which share an origin with pipeline
-- goals) from pipeline-produced ones. Nullable: every existing and extraction-created goal stays
-- NULL; only instructor-introduced goals carry a value.
ALTER TABLE learning_goal
    ADD COLUMN creation_provenance VARCHAR(32)
        CHECK (creation_provenance IN ('USER_CREATED', 'WIZARD_AI_SUBTREE'));

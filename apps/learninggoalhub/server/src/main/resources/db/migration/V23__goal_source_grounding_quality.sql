-- Strength of the source-snippet match; legacy rows remain NULL because their match tier is unknown.
ALTER TABLE goal_source
    ADD COLUMN grounding_quality VARCHAR(32)
        CHECK (grounding_quality IN (
            'EXACT_IN_SESSION', 'EXACT_IN_DOCUMENT', 'NORMALIZED', 'FRAGMENT', 'NONE'
        ));

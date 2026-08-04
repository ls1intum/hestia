-- NULL means pre-V24 data whose tier is still inferred from Bloom.
ALTER TABLE learning_goal
    ADD COLUMN role VARCHAR(16)
        CHECK (role IN ('SKILL', 'KNOWLEDGE'));

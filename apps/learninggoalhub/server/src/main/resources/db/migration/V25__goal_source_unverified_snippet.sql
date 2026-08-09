-- Preserve rejected model snippets for evaluation without exposing them as source evidence.
ALTER TABLE goal_source
    ADD COLUMN unverified_snippet TEXT;

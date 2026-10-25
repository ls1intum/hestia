-- Extracted skills and knowledge items a run left out because they failed validation. A run with
-- dropped outcomes still succeeds, so this count is what tells a degraded run from a clean one.
-- Runs before V42 have no count.
ALTER TABLE extraction_run ADD COLUMN dropped_outcomes INTEGER;

-- Anchor the parse progress countdown to the current attempt rather than the
-- exam's original creation time (which is stale after a retry). Stamped by
-- ParseExamService.preflight on every parse start; nullable so pre-existing
-- exams simply fall back to the phase-based bar.
ALTER TABLE exams ADD COLUMN parse_started_at timestamptz;

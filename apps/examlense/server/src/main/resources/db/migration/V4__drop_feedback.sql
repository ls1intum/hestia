-- Remove the general user-feedback feature (footer dialog + /api/feedback).
-- The parse_survey / parse_metrics tables are unaffected.
drop table if exists public.feedback;

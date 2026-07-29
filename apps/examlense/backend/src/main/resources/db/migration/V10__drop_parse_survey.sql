-- Remove the temporary parsing-quality survey feature (submit UI + /api/parse-survey + admin tab).
-- parse_metrics is a separate feature and is unaffected.
drop table if exists public.parse_survey;

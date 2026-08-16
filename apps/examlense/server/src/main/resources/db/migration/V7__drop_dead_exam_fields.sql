-- Drop exam columns that are no longer used by any consumer.
--
--   semester         parsed + echoed in the API but never read by the solver,
--                    prompts, or frontend.
--   instructor_name  only ever settable via the create/patch API; never parsed,
--                    read, or displayed.
--   total_points     only PATCH-settable and copied on duplicate; nothing ever
--                    computes or renders it.
--   parse_raw_text   written during parsing for debugging but never read back;
--                    shipped in every list response to a frontend that ignores
--                    it (and could be a large text blob).
alter table public.exams
  drop column if exists semester,
  drop column if exists instructor_name,
  drop column if exists total_points,
  drop column if exists parse_raw_text;

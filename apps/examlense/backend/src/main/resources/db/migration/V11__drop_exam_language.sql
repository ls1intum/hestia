-- Drop the per-exam content language. The solver now always answers in the same
-- language as the questions (no forced-language override), so the column and its
-- check constraint are no longer read anywhere.
alter table public.exams drop column if exists language;

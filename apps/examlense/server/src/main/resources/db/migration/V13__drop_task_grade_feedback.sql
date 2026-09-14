-- Drop the per-grade written comment. No requirement asks an instructor for one:
-- the grading view only ever sends `feedback: null`, and nothing reads the column
-- back, so every row carries a null that no screen can show or edit.
alter table public.task_grades drop column if exists feedback;

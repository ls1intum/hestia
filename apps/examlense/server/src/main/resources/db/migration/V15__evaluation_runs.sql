-- QA7 asks for the model version and the thinking level of every evaluation run.
-- ExamLense sends no reasoning parameter, so a run always uses the solver's own
-- default; `thinking_level` records what that default is, taken from the model
-- catalog. Null means the model does not reason by default (or is a retired entry
-- that can no longer be called at all).
--
-- The vocabulary spans three providers that each describe depth differently, so it
-- covers `dynamic` (Gemini 2.5's self-adjusting budget) alongside the effort ladder.
-- The solver model was already pinned on the exam, but nothing recorded when a
-- run happened or how hard the model had been asked to think, so two runs of the
-- same exam were indistinguishable after the fact.
--
-- An exam has at most one run: starting an evaluation deletes the previous
-- answers and auto grades, so there is only ever one live answer set to account
-- for. The unique constraint holds that rule, and re-solving replaces the row.
-- Comparing two models means duplicating the exam, which is what the duplicate
-- action already exists for.
create table if not exists public.evaluation_runs (
  id             uuid primary key default gen_random_uuid(),
  exam_id        uuid not null unique references public.exams(id) on delete cascade,
  solver_model   text not null,
  thinking_level text
      check (thinking_level in ('off', 'dynamic', 'minimal', 'low', 'medium', 'high', 'xhigh')),
  started_at     timestamptz not null default now()
);

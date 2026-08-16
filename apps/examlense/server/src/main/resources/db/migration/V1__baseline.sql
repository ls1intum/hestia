-- Phase 0 baseline schema for the standalone Postgres backend (migration off Supabase).
-- Consolidated from the final state of the 29 Supabase migrations, transcribed faithfully
-- EXCEPT: no RLS, no realtime publication, no storage buckets/policies, no `auth` schema.
-- `owner_id` / `user_id` / `graded_by` are plain uuid columns (no FK to a users table yet;
-- real auth/users arrive in a later phase). The two shift_and_insert_* functions keep their
-- position-shifting logic but drop the `auth.uid()` ownership guard (single-user for now).

create extension if not exists pgcrypto;  -- gen_random_uuid()

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

create table public.exams (
  id                uuid primary key default gen_random_uuid(),
  title             text not null default '',
  course            text,
  semester          text,
  instructor_name   text,
  total_points      numeric,
  language          text not null default 'en' check (language in ('de','en','other')),
  source            text not null check (source in ('pdf','manual')),
  source_file_url   text,
  status            text not null default 'draft'
                      check (status in ('draft','parsing','failed','ready','evaluating','grading','finished')),
  owner_id          uuid not null,
  parse_error       text,
  parse_phase       text,
  parser_model      text,
  solver_model      text,
  parse_raw_text    text,
  learning_goal_ids jsonb,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

create table public.sections (
  id               uuid primary key default gen_random_uuid(),
  exam_id          uuid not null references public.exams(id) on delete cascade,
  position         integer not null,
  name             text not null default '',
  confirmed_at     timestamptz,
  solve_started_at timestamptz,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

create table public.tasks (
  id                uuid primary key default gen_random_uuid(),
  exam_id           uuid not null references public.exams(id) on delete cascade,
  section_id        uuid references public.sections(id) on delete set null,
  position          integer not null,
  section           text,
  type              text not null check (type in ('single_choice','multiple_choice','text')),
  prompt            text not null default '',
  options           jsonb,
  reference_answer  text,
  points            numeric,
  parse_confidence  text check (parse_confidence in ('high','medium','low')),
  learning_goal_ids jsonb,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

create table public.section_blocks (
  id          uuid primary key default gen_random_uuid(),
  section_id  uuid not null references public.sections(id) on delete cascade,
  exam_id     uuid not null,
  position    integer not null default 0,
  content     text not null default '',
  kind        text not null default 'context' check (kind in ('context','figure')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table public.section_figures (
  id           uuid primary key default gen_random_uuid(),
  block_id     uuid not null references public.section_blocks(id) on delete cascade,
  position     integer not null default 0,
  storage_path text not null,
  source       text not null default 'upload' check (source in ('pdf','upload')),
  caption      text,
  created_at   timestamptz not null default now()
);

create table public.task_answers (
  id                  uuid primary key default gen_random_uuid(),
  task_id             uuid not null,
  exam_id             uuid not null,
  selected_option_ids uuid[] not null default array[]::uuid[],
  answer_text         text,
  reasoning           text,
  provider            text not null,
  model               text not null,
  created_at          timestamptz not null default now()
);

create table public.task_grades (
  id          uuid primary key default gen_random_uuid(),
  task_id     uuid not null unique,
  exam_id     uuid not null,
  score       numeric,
  auto_graded boolean not null default false,
  feedback    text,
  graded_by   uuid,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table public.feedback (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null,
  exam_id    uuid,
  message    text not null,
  responses  jsonb,
  read       boolean not null default false,
  created_at timestamptz not null default now()
);

create table public.parse_metrics (
  id                uuid primary key default gen_random_uuid(),
  created_at        timestamptz not null default now(),
  exam_id           uuid references public.exams(id) on delete set null,
  owner_id          uuid,
  parser_model      text not null,
  pdf_mode          text,
  page_count        int,
  duration_ms       int,
  llm_ms            int,
  prompt_tokens     int,
  completion_tokens int,
  total_tokens      int,
  success           boolean not null,
  error             text
);

create table public.parse_survey (
  id                  uuid primary key default gen_random_uuid(),
  exam_id             uuid not null,
  user_id             uuid not null,
  speed               smallint check (speed between 1 and 10),
  content_correctness smallint check (content_correctness between 1 and 10),
  structure           smallint check (structure between 1 and 10),
  created_at          timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------

create index idx_exams_owner_id            on public.exams(owner_id);
create index sections_exam_id_position_idx on public.sections(exam_id, position);
create index tasks_exam_position_idx       on public.tasks(exam_id, position);
create index tasks_section_id_idx          on public.tasks(section_id);
create index idx_section_blocks_section_id on public.section_blocks(section_id);
create index idx_section_blocks_exam_id    on public.section_blocks(exam_id);
create index idx_section_figures_block_id  on public.section_figures(block_id);
create index idx_task_answers_exam_id      on public.task_answers(exam_id);
create index idx_task_answers_task_id      on public.task_answers(task_id);
create index task_grades_exam_id_idx       on public.task_grades(exam_id);
create index parse_metrics_parser_model_idx on public.parse_metrics(parser_model);
create index parse_metrics_created_at_idx   on public.parse_metrics(created_at);
create index parse_survey_exam_id_idx        on public.parse_survey(exam_id);

-- ---------------------------------------------------------------------------
-- Functions & triggers
-- ---------------------------------------------------------------------------

-- Auto-maintain updated_at on row change.
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create trigger exams_set_updated_at          before update on public.exams          for each row execute function public.set_updated_at();
create trigger sections_set_updated_at       before update on public.sections       for each row execute function public.set_updated_at();
create trigger tasks_set_updated_at          before update on public.tasks          for each row execute function public.set_updated_at();
create trigger set_updated_at_section_blocks before update on public.section_blocks for each row execute function public.set_updated_at();
create trigger trg_task_grades_updated_at    before update on public.task_grades    for each row execute function public.set_updated_at();

-- When every task of an `evaluating` exam has at least one answer, flip it to `grading`.
create or replace function public.maybe_finalize_evaluation()
returns trigger
language plpgsql
as $$
declare
  total_tasks integer;
  answered integer;
  current_status text;
begin
  select status into current_status from public.exams where id = new.exam_id;
  if current_status is distinct from 'evaluating' then
    return new;
  end if;

  select count(*) into total_tasks from public.tasks where exam_id = new.exam_id;
  select count(distinct task_id) into answered
    from public.task_answers where exam_id = new.exam_id;

  if total_tasks > 0 and answered >= total_tasks then
    update public.exams set status = 'grading' where id = new.exam_id;
  end if;

  return new;
end;
$$;

create trigger trg_maybe_finalize_evaluation
  after insert on public.task_answers
  for each row execute function public.maybe_finalize_evaluation();

-- Insert a task at p_position, shifting downstream tasks (and same-section blocks) up by 1.
-- (Ownership guard from the Supabase version dropped: single-user for now.)
create or replace function public.shift_and_insert_task(
  p_exam_id uuid, p_position integer, p_type text,
  p_prompt text default ''::text, p_options jsonb default null::jsonb,
  p_reference_answer text default null::text, p_section text default null::text,
  p_points numeric default null::numeric, p_section_id uuid default null::uuid
)
returns public.tasks
language plpgsql
set search_path to 'public'
as $$
declare
  inserted public.tasks;
begin
  update public.tasks
    set position = position + 1
    where exam_id = p_exam_id
      and position >= p_position
      and ((section_id is null and p_section_id is null) or section_id = p_section_id);

  if p_section_id is not null then
    update public.section_blocks
      set position = position + 1
      where section_id = p_section_id
        and position >= p_position;
  end if;

  insert into public.tasks (exam_id, position, type, prompt, options, reference_answer, section, points, section_id)
  values (p_exam_id, p_position, p_type, p_prompt, p_options, p_reference_answer, p_section, p_points, p_section_id)
  returning * into inserted;

  return inserted;
end;
$$;

-- Insert a section block at p_position, shifting downstream blocks and tasks of that section.
-- (Ownership guard dropped: single-user for now.)
create or replace function public.shift_and_insert_block(
  p_exam_id uuid, p_section_id uuid, p_position integer,
  p_content text default ''::text, p_kind text default 'context'::text
)
returns public.section_blocks
language plpgsql
set search_path to 'public'
as $$
declare
  inserted public.section_blocks;
begin
  update public.section_blocks
    set position = position + 1
    where section_id = p_section_id
      and position >= p_position;

  update public.tasks
    set position = position + 1
    where exam_id = p_exam_id
      and section_id = p_section_id
      and position >= p_position;

  insert into public.section_blocks (exam_id, section_id, position, content, kind)
  values (p_exam_id, p_section_id, p_position, p_content, p_kind)
  returning * into inserted;

  return inserted;
end;
$$;

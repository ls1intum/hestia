-- An AI answer is the current result for a task, and a grade evaluates that
-- specific answer. Normalize legacy data before enforcing those cardinalities.

-- Keep the newest answer for each task. UUID is a deterministic tie-breaker for
-- rows created in the same timestamp tick.
delete from public.task_answers a
using (
  select id
  from (
    select id,
           row_number() over (
             partition by task_id
             order by created_at desc nulls last, id desc
           ) as answer_rank
    from public.task_answers
  ) ranked
  where answer_rank > 1
) duplicate
where a.id = duplicate.id;

alter table public.task_answers
  add constraint task_answers_task_id_key unique (task_id);

alter table public.task_grades add column answer_id uuid;

update public.task_grades g
set answer_id = a.id
from public.task_answers a
where a.task_id = g.task_id;

-- A grade without an answer cannot be represented by the answer-owned model.
delete from public.task_grades where answer_id is null;

alter table public.task_grades
  drop constraint task_grades_task_id_fkey,
  drop constraint task_grades_exam_id_fkey,
  drop constraint task_grades_task_id_key,
  drop column task_id,
  drop column exam_id,
  alter column answer_id set not null,
  add constraint task_grades_answer_id_key unique (answer_id),
  add constraint task_grades_answer_id_fkey
    foreign key (answer_id) references public.task_answers(id) on delete cascade;

drop index if exists public.task_grades_exam_id_idx;

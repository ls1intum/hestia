-- Lifecycle integrity for task_answers / task_grades, and removal of DB-side
-- business logic that Java now owns.
--
-- 1) task_answers and task_grades were created without foreign keys, so
--    deleting a task or exam orphaned their rows forever (the exam-delete code
--    even assumed a cascade that didn't exist). Clean up existing orphans,
--    then add cascading FKs so every delete path is covered.
--
-- 2) shift_and_insert_task / shift_and_insert_block are dead: CrudService
--    reimplements the position-shifting inserts in Java transactions.
--
-- 3) maybe_finalize_evaluation duplicated SolveExamService's finalize step
--    (updateStatusIfCurrent evaluating→grading after the dispatch joins).
--    One owner for that transition: Java. Drop the trigger so the state
--    machine isn't split across two layers.

-- Orphans first, or the FKs can't be created.
delete from public.task_answers a where not exists (select 1 from public.tasks t where t.id = a.task_id);
delete from public.task_answers a where not exists (select 1 from public.exams e where e.id = a.exam_id);
delete from public.task_grades g where not exists (select 1 from public.tasks t where t.id = g.task_id);
delete from public.task_grades g where not exists (select 1 from public.exams e where e.id = g.exam_id);

alter table public.task_answers
  add constraint task_answers_task_id_fkey foreign key (task_id) references public.tasks(id) on delete cascade,
  add constraint task_answers_exam_id_fkey foreign key (exam_id) references public.exams(id) on delete cascade;

alter table public.task_grades
  add constraint task_grades_task_id_fkey foreign key (task_id) references public.tasks(id) on delete cascade,
  add constraint task_grades_exam_id_fkey foreign key (exam_id) references public.exams(id) on delete cascade;

drop trigger if exists trg_maybe_finalize_evaluation on public.task_answers;
drop function if exists public.maybe_finalize_evaluation();
drop function if exists public.shift_and_insert_task(uuid, integer, text, text, jsonb, text, text, numeric, uuid);
drop function if exists public.shift_and_insert_block(uuid, uuid, integer, text, text);

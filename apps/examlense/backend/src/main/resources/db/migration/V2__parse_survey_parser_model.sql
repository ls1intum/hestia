-- Denormalize the parser model onto each survey response.
--
-- parse_survey has no FK to exams (only an index on exam_id) and deliberately
-- outlives the exam. Grouping survey quality/speed scores by model via a join to
-- exams.parser_model therefore loses attribution the moment an exam is deleted.
-- Storing the model on the survey row makes the "which model does the job best"
-- rollup independent of the exam's lifetime: deleted exams still flow into it.
--
-- Backfill existing rows from the exam while it is still around; new rows are
-- stamped at submit time by ParseSurveyController.
alter table public.parse_survey add column parser_model text;

update public.parse_survey s
   set parser_model = e.parser_model
  from public.exams e
 where e.id = s.exam_id;

create index parse_survey_parser_model_idx on public.parse_survey(parser_model);

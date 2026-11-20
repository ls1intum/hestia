-- Exams belong to a core course; learning goals of tasks are alignment links in core.

-- Exams without a course cannot be assigned to one (staging data is disposable); children cascade.
delete from assessment.exams where course_id is null;

alter table assessment.exams drop column course;
alter table assessment.exams drop column lgh_course_id;

alter table assessment.exams drop constraint exams_course_id_fkey;
alter table assessment.exams alter column course_id set not null;
alter table assessment.exams
    add constraint exams_course_id_fkey foreign key (course_id) references core.course (id) on delete cascade;

-- A task's goals are core.alignment_link rows (target module assessment, type TASK).
alter table assessment.tasks drop column learning_goal_ids;

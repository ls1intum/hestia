-- Assessment (formerly ExamLense) under the identity and tenancy model: the module's own users and
-- tokens are replaced by core.app_user, every table carries org_id and is isolated by row-level
-- security, and an exam is shared through a core course instead of belonging to one user.

-- Own users and bearer tokens are gone: login is the session login of core.
alter table assessment.exams drop column owner_id;
drop table assessment.user_tokens;

alter table assessment.llm_usage drop constraint llm_usage_user_id_fkey;
alter table assessment.llm_usage
    add constraint llm_usage_user_id_fkey foreign key (user_id) references core.app_user (id) on delete cascade;

drop table assessment.users;

-- Who created the exam; access goes through the course, or to the creator if the exam has none.
alter table assessment.exams add column created_by uuid references core.app_user (id) on delete set null;
alter table assessment.exams add column course_id uuid references core.course (id) on delete set null;
create index exams_created_by_idx on assessment.exams (created_by);
create index exams_course_id_idx on assessment.exams (course_id);

alter table assessment.parse_metrics
    add constraint parse_metrics_owner_id_fkey foreign key (owner_id) references core.app_user (id) on delete set null;
-- Who graded: kept as a record only.
alter table assessment.task_grades
    add constraint task_grades_graded_by_fkey foreign key (graded_by) references core.app_user (id) on delete set null;

alter table assessment.evaluation_runs add column org_id uuid not null references core.organisation (id);
alter table assessment.evaluation_runs enable row level security;
create policy org_isolation on assessment.evaluation_runs to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table assessment.exams add column org_id uuid not null references core.organisation (id);
alter table assessment.exams enable row level security;
create policy org_isolation on assessment.exams to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table assessment.llm_usage add column org_id uuid not null references core.organisation (id);
alter table assessment.llm_usage enable row level security;
create policy org_isolation on assessment.llm_usage to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table assessment.parse_metrics add column org_id uuid not null references core.organisation (id);
alter table assessment.parse_metrics enable row level security;
create policy org_isolation on assessment.parse_metrics to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table assessment.section_blocks add column org_id uuid not null references core.organisation (id);
alter table assessment.section_blocks enable row level security;
create policy org_isolation on assessment.section_blocks to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table assessment.section_figures add column org_id uuid not null references core.organisation (id);
alter table assessment.section_figures enable row level security;
create policy org_isolation on assessment.section_figures to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table assessment.sections add column org_id uuid not null references core.organisation (id);
alter table assessment.sections enable row level security;
create policy org_isolation on assessment.sections to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table assessment.task_answers add column org_id uuid not null references core.organisation (id);
alter table assessment.task_answers enable row level security;
create policy org_isolation on assessment.task_answers to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table assessment.task_grades add column org_id uuid not null references core.organisation (id);
alter table assessment.task_grades enable row level security;
create policy org_isolation on assessment.task_grades to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table assessment.tasks add column org_id uuid not null references core.organisation (id);
alter table assessment.tasks enable row level security;
create policy org_isolation on assessment.tasks to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);


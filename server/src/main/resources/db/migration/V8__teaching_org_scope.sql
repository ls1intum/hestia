-- Organisation scope and course roles for the teaching module (package 4: identity and tenancy).
-- All three tables get org_id and row-level security (policy org_isolation, see V6). Courses hang on
-- a core.course and take their access from its roles; slide templates belong to one user; a session
-- without a course belongs to the user who created it.

alter table teaching.courses
    add column org_id uuid not null references core.organisation (id),
    add column core_course_id uuid not null unique references core.course (id) on delete cascade,
    drop column owner_id;

alter table teaching.slide_templates
    add column org_id uuid not null references core.organisation (id),
    drop column owner_id,
    add column owner_id uuid not null references core.app_user (id) on delete cascade;

alter table teaching.workshop_sessions
    add column org_id uuid not null references core.organisation (id),
    drop column owner_id,
    add column created_by uuid references core.app_user (id) on delete set null;

create index courses_org_id on teaching.courses (org_id);
create index slide_templates_owner_id on teaching.slide_templates (owner_id);
create index workshop_sessions_org_id on teaching.workshop_sessions (org_id);
create index workshop_sessions_course_id on teaching.workshop_sessions (course_id);
create index workshop_sessions_created_by on teaching.workshop_sessions (created_by);

alter table teaching.courses enable row level security;
create policy org_isolation on teaching.courses to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table teaching.slide_templates enable row level security;
create policy org_isolation on teaching.slide_templates to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table teaching.workshop_sessions enable row level security;
create policy org_isolation on teaching.workshop_sessions to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

-- Teaching uses the core course (package 6): the module's own course table goes away. What a course
-- holds only for Workshopper (display order, wizard draft, template) moves to teaching_plan, keyed by
-- the core course; every session belongs to a core course (uuid). Staging data is disposable, so the
-- sessions and templates of the old model are deleted instead of migrated.

delete from teaching.workshop_sessions;
delete from teaching.slide_templates;

alter table teaching.workshop_sessions drop column course_id;

drop table teaching.courses;

alter table teaching.workshop_sessions
    add column course_id uuid not null references core.course (id) on delete cascade;

create index workshop_sessions_course_id on teaching.workshop_sessions (course_id);

create table teaching.teaching_plan (
    course_id uuid primary key references core.course (id) on delete cascade,
    org_id uuid not null references core.organisation (id),
    display_order integer,
    draft_state_json jsonb,
    status character varying(20),
    current_step character varying(50),
    template_id character varying(255) references teaching.slide_templates (id) on delete set null,
    created_at timestamp without time zone,
    updated_at timestamp without time zone
);

create index teaching_plan_org_id on teaching.teaching_plan (org_id);

alter table teaching.teaching_plan enable row level security;
create policy org_isolation on teaching.teaching_plan to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

-- Organisation scope for the materials module (package 4): every table carries org_id and is isolated
-- by row-level security, like the core tables. Every materials course belongs to a core.course, on
-- which the course roles are checked. Data need not survive (fresh database).

alter table materials.course add column org_id uuid not null references core.organisation (id);
create index course_org_id_idx on materials.course (org_id);
alter table materials.document add column org_id uuid not null references core.organisation (id);
create index document_org_id_idx on materials.document (org_id);
alter table materials.document_content add column org_id uuid not null references core.organisation (id);
create index document_content_org_id_idx on materials.document_content (org_id);
alter table materials.document_section add column org_id uuid not null references core.organisation (id);
create index document_section_org_id_idx on materials.document_section (org_id);
alter table materials.exam_block add column org_id uuid not null references core.organisation (id);
create index exam_block_org_id_idx on materials.exam_block (org_id);
alter table materials.exam_submission add column org_id uuid not null references core.organisation (id);
create index exam_submission_org_id_idx on materials.exam_submission (org_id);
alter table materials.extraction_run add column org_id uuid not null references core.organisation (id);
create index extraction_run_org_id_idx on materials.extraction_run (org_id);
alter table materials.goal_relationship add column org_id uuid not null references core.organisation (id);
create index goal_relationship_org_id_idx on materials.goal_relationship (org_id);
alter table materials.goal_source add column org_id uuid not null references core.organisation (id);
create index goal_source_org_id_idx on materials.goal_source (org_id);
alter table materials.hierarchy_node add column org_id uuid not null references core.organisation (id);
create index hierarchy_node_org_id_idx on materials.hierarchy_node (org_id);
alter table materials.learning_goal add column org_id uuid not null references core.organisation (id);
create index learning_goal_org_id_idx on materials.learning_goal (org_id);
alter table materials.page_description add column org_id uuid not null references core.organisation (id);
create index page_description_org_id_idx on materials.page_description (org_id);
alter table materials.intake_submission add column org_id uuid not null references core.organisation (id);
create index intake_submission_org_id_idx on materials.intake_submission (org_id);
alter table materials.intake_file add column org_id uuid not null references core.organisation (id);
create index intake_file_org_id_idx on materials.intake_file (org_id);

alter table materials.course add column core_course_id uuid not null unique references core.course (id) on delete cascade;

alter table materials.course enable row level security;
create policy org_isolation on materials.course to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.document enable row level security;
create policy org_isolation on materials.document to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.document_content enable row level security;
create policy org_isolation on materials.document_content to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.document_section enable row level security;
create policy org_isolation on materials.document_section to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.exam_block enable row level security;
create policy org_isolation on materials.exam_block to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.exam_submission enable row level security;
create policy org_isolation on materials.exam_submission to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.extraction_run enable row level security;
create policy org_isolation on materials.extraction_run to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.goal_relationship enable row level security;
create policy org_isolation on materials.goal_relationship to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.goal_source enable row level security;
create policy org_isolation on materials.goal_source to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.hierarchy_node enable row level security;
create policy org_isolation on materials.hierarchy_node to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.learning_goal enable row level security;
create policy org_isolation on materials.learning_goal to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.page_description enable row level security;
create policy org_isolation on materials.page_description to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.intake_submission enable row level security;
create policy org_isolation on materials.intake_submission to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.intake_file enable row level security;
create policy org_isolation on materials.intake_file to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

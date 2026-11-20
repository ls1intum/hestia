-- Package 6, materials: the course is core.course and the learning goals live in core. Materials keeps
-- its course settings (material_settings), the placement of a goal in the Topic tree (goal_placement) and
-- the evidence of a goal in a document (goal_source). Production data need not survive: the tables whose
-- key changes are emptied.

truncate materials.course cascade;

drop table materials.goal_relationship;
drop table materials.goal_source;
drop table materials.learning_goal;

alter table materials.document drop column course_id;
alter table materials.document add column course_id uuid not null references core.course (id) on delete cascade;
create index idx_document_course on materials.document (course_id);

alter table materials.exam_submission drop column course_id;
alter table materials.exam_submission add column course_id uuid not null references core.course (id) on delete cascade;
create index idx_exam_submission_course on materials.exam_submission (course_id);

alter table materials.extraction_run drop column course_id;
alter table materials.extraction_run add column course_id uuid not null references core.course (id) on delete cascade;
create index idx_extraction_run_course on materials.extraction_run (course_id);

alter table materials.hierarchy_node drop column course_id;
alter table materials.hierarchy_node add column course_id uuid not null references core.course (id) on delete cascade;
create index idx_hierarchy_node_course on materials.hierarchy_node (course_id);

drop table materials.course;

create table materials.material_settings (
    course_id uuid primary key references core.course (id) on delete cascade,
    org_id uuid not null references core.organisation (id),
    output_language varchar(16),
    figures_enabled boolean not null default false,
    skills_reviewed_at timestamptz
);
create index material_settings_org_id_idx on materials.material_settings (org_id);

create table materials.goal_placement (
    goal_id uuid primary key references core.learning_goal (id) on delete cascade,
    org_id uuid not null references core.organisation (id),
    hierarchy_node_id bigint references materials.hierarchy_node (id) on delete set null,
    exam_block_id bigint references materials.exam_block (id) on delete set null,
    lecture_order integer
);
create index goal_placement_org_id_idx on materials.goal_placement (org_id);
create index goal_placement_hierarchy_idx on materials.goal_placement (hierarchy_node_id);
create index goal_placement_exam_block_idx on materials.goal_placement (exam_block_id);

create table materials.goal_source (
    goal_id uuid not null references core.learning_goal (id) on delete cascade,
    document_id bigint not null references materials.document (id) on delete cascade,
    org_id uuid not null references core.organisation (id),
    snippet text not null,
    page integer,
    grounded boolean not null default false,
    grounding_quality varchar(32) check (grounding_quality in
        ('EXACT_IN_SESSION', 'EXACT_IN_DOCUMENT', 'NORMALIZED', 'FRAGMENT', 'NONE')),
    unverified_snippet text,
    highlight_rects jsonb,
    evidence_kind varchar(16) not null check (evidence_kind in ('TEXT', 'FIGURE', 'UNSUPPORTED')),
    primary key (goal_id, document_id)
);
create index idx_goal_source_document on materials.goal_source (document_id);
create index goal_source_org_id_idx on materials.goal_source (org_id);

alter table materials.material_settings enable row level security;
create policy org_isolation on materials.material_settings to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.goal_placement enable row level security;
create policy org_isolation on materials.goal_placement to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table materials.goal_source enable row level security;
create policy org_isolation on materials.goal_source to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

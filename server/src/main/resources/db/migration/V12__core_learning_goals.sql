-- Learning goals live only in core from here on. A goal has a stable identity (learning_goal) and
-- versions of its wording (learning_goal_version); every change of text, short label, kind, role,
-- Bloom or SOLO level writes a new version. Goals are removed softly (removed_at), so links to them
-- survive for the review list. alignment_link connects a goal with an object of a domain module
-- (document, teaching session, exam task); target_id is that module's id and deliberately has no
-- foreign key. Every table carries org_id and is isolated by row-level security.

create table core.learning_goal (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references core.organisation (id),
    course_id uuid not null references core.course (id) on delete cascade,
    current_version int not null,
    status text not null check (status in ('PENDING', 'APPROVED')),
    origin text not null check (origin in ('EXTRACTED', 'SYNTHESIZED', 'EXAM', 'TERMINAL', 'GAP', 'MANUAL')),
    -- USER_CREATED | WIZARD_AI_SUBTREE
    creation_provenance text,
    created_via text not null check (created_via in ('materials', 'teaching', 'assessment')),
    -- null = created by a model or by a deleted user
    created_by uuid references core.app_user (id) on delete set null,
    created_at timestamptz not null default now(),
    removed_at timestamptz
);

create index learning_goal_course_id on core.learning_goal (course_id);

create table core.learning_goal_version (
    goal_id uuid not null references core.learning_goal (id) on delete cascade,
    version int not null,
    org_id uuid not null references core.organisation (id),
    text text not null,
    short_label text,
    kind text not null check (kind in ('EXPLICIT', 'IMPLICIT')),
    role text check (role in ('SKILL', 'KNOWLEDGE')),
    bloom_level text,
    solo_level text,
    changed_by uuid references core.app_user (id) on delete set null,
    created_at timestamptz not null default now(),
    primary key (goal_id, version)
);

create table core.goal_relationship (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references core.organisation (id),
    source_goal_id uuid not null references core.learning_goal (id) on delete cascade,
    target_goal_id uuid not null references core.learning_goal (id) on delete cascade,
    type text not null,
    confidence double precision not null,
    origin text not null,
    created_at timestamptz not null default now(),
    unique (source_goal_id, target_goal_id, type)
);

create index goal_relationship_target_goal_id on core.goal_relationship (target_goal_id);

create table core.alignment_link (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references core.organisation (id),
    course_id uuid not null references core.course (id) on delete cascade,
    goal_id uuid not null references core.learning_goal (id) on delete cascade,
    target_module text not null check (target_module in ('materials', 'teaching', 'assessment', 'catalog')),
    -- DOCUMENT | SESSION | TASK
    target_type text not null,
    target_id text not null,
    goal_version_seen int not null,
    review_state text not null default 'OK' check (review_state in ('OK', 'REVIEW')),
    review_reason text check (review_reason in ('GOAL_CHANGED', 'GOAL_REMOVED')),
    created_by uuid references core.app_user (id) on delete set null,
    created_at timestamptz not null default now(),
    unique (goal_id, target_module, target_type, target_id)
);

create index alignment_link_target on core.alignment_link (target_module, target_type, target_id);
create index alignment_link_course_id on core.alignment_link (course_id);

alter table core.learning_goal enable row level security;
create policy org_isolation on core.learning_goal to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table core.learning_goal_version enable row level security;
create policy org_isolation on core.learning_goal_version to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table core.goal_relationship enable row level security;
create policy org_isolation on core.goal_relationship to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table core.alignment_link enable row level security;
create policy org_isolation on core.alignment_link to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

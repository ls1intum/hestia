-- Identity and tenancy of the core module: organisations (universities), users, courses and the
-- per-course roles. The tenant is the organisation; every table except core.organisation carries
-- org_id and is isolated by row-level security (policy org_isolation) for hestia_app. The
-- application sets hestia.org_id on every connection it takes from the pool (OrgAwareDataSource).

create table core.organisation (
    id uuid primary key default gen_random_uuid(),
    -- SAML attribute schacHomeOrganization, e.g. the university's domain
    schac_home_organization text not null unique,
    display_name text,
    created_at timestamptz not null default now()
);

create table core.app_user (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references core.organisation (id),
    -- eduPersonPrincipalName, fallback the SAML NameID
    external_id text not null,
    display_name text,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz,
    unique (org_id, external_id)
);

create table core.course (
    id uuid primary key default gen_random_uuid(),
    org_id uuid not null references core.organisation (id),
    title text not null,
    -- the user's catch-all course
    personal boolean not null default false,
    created_by uuid references core.app_user (id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create unique index course_one_personal_per_user on core.course (created_by) where personal;
create index course_org_id on core.course (org_id);

create table core.course_membership (
    course_id uuid not null references core.course (id) on delete cascade,
    user_id uuid not null references core.app_user (id) on delete cascade,
    org_id uuid not null references core.organisation (id),
    role text not null check (role in ('OWNER', 'EDITOR', 'VIEWER')),
    created_at timestamptz not null default now(),
    primary key (course_id, user_id)
);

create index course_membership_user_id on core.course_membership (user_id);

-- The organisation is looked up at login, before the tenant is known; it holds no tenant data.
alter table core.app_user enable row level security;
create policy org_isolation on core.app_user to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table core.course enable row level security;
create policy org_isolation on core.course to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table core.course_membership enable row level security;
create policy org_isolation on core.course_membership to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

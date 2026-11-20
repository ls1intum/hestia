-- Module ai: model calls, the jobs they belong to, and the consent log.

-- One unit of work a person starts (read in an exam, let a model solve it). Limits count these.
create table ai.llm_job (
    id         uuid primary key default gen_random_uuid(),
    org_id     uuid not null references core.organisation (id),
    user_id    uuid references core.app_user (id) on delete set null,
    course_id  uuid references core.course (id) on delete set null,
    module     text not null,
    purpose    text not null,
    provider   text not null,
    model      text,
    external   boolean not null,
    created_at timestamptz not null default now()
);
create index llm_job_user_purpose_created on ai.llm_job (user_id, purpose, created_at);

-- Every answered model call, for cost attribution.
create table ai.llm_call (
    id                uuid primary key default gen_random_uuid(),
    org_id            uuid not null references core.organisation (id),
    job_id            uuid references ai.llm_job (id) on delete set null,
    user_id           uuid references core.app_user (id) on delete set null,
    course_id         uuid references core.course (id) on delete set null,
    module            text not null,
    purpose           text not null,
    provider          text not null,
    model             text,
    external          boolean not null,
    prompt_tokens     integer,
    completion_tokens integer,
    created_at        timestamptz not null default now()
);
create index llm_call_job on ai.llm_call (job_id);
create index llm_call_created on ai.llm_call (created_at);

-- Append-only log of consents; the current state is a user's latest row per scope.
create table ai.consent_event (
    id             uuid primary key default gen_random_uuid(),
    org_id         uuid not null references core.organisation (id),
    user_id        uuid not null references core.app_user (id) on delete cascade,
    scope          text not null,
    action         text not null check (action in ('GRANTED', 'REVOKED')),
    notice_version text not null,
    created_at     timestamptz not null default now()
);
create index consent_event_user_scope on ai.consent_event (user_id, scope, created_at);

-- The application may only add to the log, never change or remove an entry. Deleting a user still
-- removes theirs: the foreign key's cascade runs with the table owner's rights.
revoke update, delete on ai.consent_event from hestia_app;

alter table ai.llm_job enable row level security;
create policy org_isolation on ai.llm_job to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table ai.llm_call enable row level security;
create policy org_isolation on ai.llm_call to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

alter table ai.consent_event enable row level security;
create policy org_isolation on ai.consent_event to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

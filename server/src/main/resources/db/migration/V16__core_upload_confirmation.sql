-- Upload confirmation: before a user's first upload they confirm that uploads contain no personal
-- data of others (e.g. student names or ID numbers). Append-only log, like ai.consent_event; a
-- confirmation counts for the notice version it was given for, so a changed text asks again.
create table core.upload_confirmation (
    id             uuid primary key default gen_random_uuid(),
    org_id         uuid not null references core.organisation (id),
    user_id        uuid not null references core.app_user (id) on delete cascade,
    notice_version text not null,
    created_at     timestamptz not null default now()
);
create index upload_confirmation_user on core.upload_confirmation (user_id, created_at);

-- The application may only add to the log. Deleting a user still removes theirs: the foreign key's
-- cascade runs with the table owner's rights.
revoke update, delete on core.upload_confirmation from hestia_app;

alter table core.upload_confirmation enable row level security;
create policy org_isolation on core.upload_confirmation to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

-- Inactivity deletion: accounts without a login for a year are deleted with their content. Before
-- that the user is warned twice (by default after 300 and 350 days). One row per warning; it refers
-- to the inactive period through last_seen_at, so a new login (a newer last_seen_at) starts a new
-- period and older warnings no longer count. The client shows unacknowledged warnings after login.
create table core.inactivity_warning (
    id              uuid primary key default gen_random_uuid(),
    org_id          uuid not null references core.organisation (id),
    user_id         uuid not null references core.app_user (id) on delete cascade,
    after_days      int not null,
    last_seen_at    timestamptz not null,
    created_at      timestamptz not null default now(),
    acknowledged_at timestamptz,
    unique (user_id, last_seen_at, after_days)
);

alter table core.inactivity_warning enable row level security;
create policy org_isolation on core.inactivity_warning to hestia_app
    using (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid)
    with check (org_id = nullif(current_setting('hestia.org_id', true), '')::uuid);

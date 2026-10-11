-- Real users, interim token auth, and per-user LLM metering.
--
-- Until now `owner_id` was a plain uuid stamped with a single fixed placeholder
-- (app.shared.DefaultUser.ID) because there was no users table — every caller
-- authenticated as that one row, which is why everyone saw every exam. This
-- migration introduces the users table those columns were always meant to
-- reference, so the owner checks already present in the code start isolating
-- real people.
--
-- Accounts are created on first visit with no input from the user (open
-- registration), identified by a server-generated `anon-…` handle. A user may
-- later replace that handle with their TUM ID, normalized (lowercased, domain
-- stripped) — deliberately the same identifier TUM's Shibboleth IdP will
-- release, so a linked account survives the SAML cutover with its exams.

-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------

create table public.users (
  id           uuid primary key default gen_random_uuid(),
  -- Starts as a server-generated `anon-…` handle so an account can be created
  -- with no input from the user; may later be replaced by their TUM ID.
  external_id  text not null unique,
  display_name text,
  is_admin     boolean not null default false,
  -- sha256 of the client IP that registered — never the address itself. Enough to
  -- cap account creation per IP without storing personal data.
  created_ip_hash text,
  created_at   timestamptz not null default now(),
  last_seen_at timestamptz
);

create index idx_users_created_ip_hash on public.users(created_ip_hash, created_at desc);

-- The pre-existing single-user placeholder, promoted to a real row. Every exam
-- created before this migration carries this id, so seeding it is what lets the
-- exams.owner_id foreign key below be added without touching exam data.
--
-- Deliberately NOT an admin. The shared bootstrap token (app.auth.token) resolves
-- to this row and its value is committed to a public repository, so an admin flag
-- here would hand user administration to anyone who reads it. Admin comes from the
-- separate app.auth.admin-token secret, or from is_admin on a real account.
insert into public.users (id, external_id, display_name, is_admin)
values (
  '00000000-0000-0000-0000-000000000001',
  'legacy-default-user',
  'Legacy default user',
  false
);

-- ---------------------------------------------------------------------------
-- Interim auth: session tokens
-- ---------------------------------------------------------------------------

-- Only ever stores sha256(token) hex — the plaintext is returned to the caller
-- once at mint time and never persisted, so a database leak does not hand over
-- live credentials.
create table public.user_tokens (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references public.users(id) on delete cascade,
  token_hash   text not null unique,
  label        text,
  created_at   timestamptz not null default now(),
  last_used_at timestamptz,
  expires_at   timestamptz,
  revoked_at   timestamptz
);

create index idx_user_tokens_user_id on public.user_tokens(user_id);

-- ---------------------------------------------------------------------------
-- Per-user LLM metering
-- ---------------------------------------------------------------------------

-- One row per LLM job admitted, so the quota is a windowed count. Kept in the
-- database rather than in memory so it survives restarts and stays correct if
-- the service is ever run as more than one instance.
create table public.llm_usage (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references public.users(id) on delete cascade,
  kind       text not null check (kind in ('parse','solve')),
  created_at timestamptz not null default now()
);

create index idx_llm_usage_user_kind_created on public.llm_usage(user_id, kind, created_at desc);

-- ---------------------------------------------------------------------------
-- Make ownership referential
-- ---------------------------------------------------------------------------

-- Safe now that every owner_id in the table resolves to the seeded legacy row.
alter table public.exams
  add constraint fk_exams_owner_id foreign key (owner_id) references public.users(id);

-- Note: parse_metrics.owner_id is intentionally left nullable and FK-free.
-- Metric rows outlive the exams they measured (see ParseMetricsController), so
-- a foreign key there would reject inserts for deleted owners.

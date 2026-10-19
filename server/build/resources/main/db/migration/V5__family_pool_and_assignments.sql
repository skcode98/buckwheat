-- The family pool, the per-member split, and head-to-member spend requests.
--
-- Two of these tables already existed from V1 and were never wired to anything, so this migration
-- has to ALTER them rather than CREATE them. `create table if not exists` against a table V1 already
-- made is silently skipped, which is how the first attempt at this file left five columns missing and
-- every push touching family_state failing at the SQL layer with no test noticing, because the table
-- was not in a TableSpec to begin with.
--
-- `bucket` is added to both transaction tables rather than derived from `member_id IS NULL`, because a
-- row created before enrolment also has a null member and is not household money. Without the column a
-- rent recorded by the head would arrive on another device indistinguishable from an ordinary spend and
-- quietly move out of the household tier. It is nullable so that a client predating this migration
-- keeps syncing: the server's payload contract marks the key optional for the same reason.

alter table transactions
    add column if not exists bucket text,
    add column if not exists assignment_id uuid,
    add column if not exists assigned_by_member_id uuid;

alter table archived_transactions
    add column if not exists bucket text,
    add column if not exists assignment_id uuid,
    add column if not exists assigned_by_member_id uuid;

-- Recreated rather than altered.
--
-- V1 keyed this on `family_id` and gave it `seq` but no `id`, so the generic sync writer -- which
-- inserts an `id` and reads rows back with `where id = ?` -- could never have addressed it. That is
-- why it sat in the schema unwritten rather than being broken.
--
-- Dropping is safe because the table is provably empty: nothing has ever had a TableSpec for it, so
-- no push or pull could have written a row. `id` is the primary key and the client sends the family id
-- as the record id, so `family_id` is a redundant copy kept only to satisfy the writer's scoping
-- column, with a unique constraint so it cannot disagree with `id`.
create table if not exists family_state_v5 (
    id uuid primary key,
    family_id uuid not null unique references families(id) on delete cascade,
    budget numeric not null default 0,
    household_tier numeric not null default 0,
    start_date bigint not null default 0,
    finish_date bigint not null default 0,
    currency text not null default '',
    household_detail_visible_to_all boolean not null default false,
    common_split_rule text not null default 'EQUAL',
    tags_visible_to_self boolean not null default true,
    family_ai_enabled boolean not null default true,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    deleted_at bigint,
    version integer not null default 1
);

drop table if exists family_state;
alter table family_state_v5 rename to family_state;

-- V3 enabled row level security on the table this migration just dropped, and the rename brings across
-- a table that has never had it. Re-enable explicitly: `family_state` is reachable through the public
-- sync API, and every read is scoped by `family_id` in application code, so an unprotected table here
-- is a cross-family read waiting to happen.
alter table family_state enable row level security;

-- Altered rather than recreated, so any allocation a client already pushed survives.
--
-- V1 constrained `period_id` to reference `budget_periods(id)`. That cannot hold the keys the Android
-- client actually uses: it derives a period key from the period's start date, because `budget_periods`
-- rows only exist for *closed* periods and the active period has no row to point at. The constraint
-- would reject every allocation with a foreign key violation, so it is dropped.
--
-- `seq`, `updated_at`, `version` and `deleted_at` are added because the generic writer emits all four
-- for every TableSpec table; without them a push of an allocation fails on an undefined column.
alter table period_limits
    drop constraint if exists period_limits_period_id_fkey,
    add column if not exists seq bigint not null default nextval('sync_sequence'),
    add column if not exists updated_at bigint not null default 0,
    add column if not exists deleted_at bigint,
    add column if not exists version integer not null default 1;

create index if not exists period_limits_family_id_idx on period_limits (family_id);

-- Genuinely new, so a plain create is correct here.
create table if not exists spend_assignments (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    period_id uuid not null,
    target_member_id uuid not null,
    created_by_member_id uuid not null,
    amount numeric not null default 0,
    category text,
    comment text not null default '',
    date bigint not null,
    status text not null default 'PENDING',
    resolved_at bigint,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    deleted_at bigint,
    version integer not null default 1
);

create index if not exists spend_assignments_family_id_idx on spend_assignments (family_id);
create index if not exists spend_assignments_period_id_idx on spend_assignments (period_id);
create index if not exists spend_assignments_target_member_id_idx on spend_assignments (target_member_id);

-- `spend_assignments` is in the sync contract, so it is reachable through the public API. It therefore
-- needs the same treatment as every other family table: row level security on, with scoping enforced
-- in application code. A brand new table is exactly the one most likely to be forgotten, because no
-- existing policy covers it.
alter table spend_assignments enable row level security;

-- `period_limits` already had it from V3 and is only altered, never recreated, so it keeps its own.

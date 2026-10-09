create extension if not exists pgcrypto;

create sequence if not exists sync_sequence;

create table families (
    id uuid primary key default gen_random_uuid(),
    created_at timestamptz not null default now()
);

create table members (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    display_name text not null,
    is_owner boolean not null default false,
    joined_at timestamptz not null default now()
);

create table invites (
    code text primary key,
    family_id uuid not null references families(id) on delete cascade,
    created_by uuid not null references members(id) on delete cascade,
    expires_at timestamptz not null,
    redeemed_at timestamptz
);

create table member_tokens (
    token_hash text primary key,
    member_id uuid not null references members(id) on delete cascade,
    family_id uuid not null references families(id) on delete cascade,
    created_at timestamptz not null default now()
);

create table budget_periods (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    budget numeric not null,
    start_date bigint not null,
    finish_date bigint not null,
    actual_finish_date bigint,
    currency text not null,
    total_spent numeric not null default 0,
    is_imported boolean not null default false,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table transactions (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    member_id uuid references members(id) on delete set null,
    type text not null,
    value numeric not null,
    spent_at bigint not null,
    comment text not null,
    category text,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table archived_transactions (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    member_id uuid references members(id) on delete set null,
    period_id uuid not null references budget_periods(id) on delete cascade,
    type text not null,
    value numeric not null,
    spent_at bigint not null,
    comment text not null,
    category text,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table family_state (
    family_id uuid primary key references families(id) on delete cascade,
    budget numeric not null,
    start_date bigint not null,
    finish_date bigint not null,
    currency text not null,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1
);

create table period_limits (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    period_id uuid not null references budget_periods(id) on delete cascade,
    member_id uuid not null references members(id) on delete cascade,
    limit_value numeric not null,
    unique (period_id, member_id)
);

create table saved_categories (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    name text not null,
    emoji text not null,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table saved_tags (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    name text not null,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table recurring_templates (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    amount numeric not null,
    comment text not null,
    day_of_month integer not null,
    enabled boolean not null default true,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table savings_goals (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    target numeric not null,
    current numeric not null default 0,
    deadline bigint,
    created_at bigint not null,
    completed boolean not null default false,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table family_settings (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    key text not null,
    value text not null,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint,
    unique (family_id, key)
);

create index on transactions (family_id, seq);
create index on archived_transactions (family_id, seq);
create index on budget_periods (family_id, seq);
create index on saved_categories (family_id, seq);
create index on saved_tags (family_id, seq);
create index on recurring_templates (family_id, seq);
create index on savings_goals (family_id, seq);
create index on family_settings (family_id, seq);
create index on member_tokens (member_id);
create index on invites (family_id);
create index on period_limits (period_id);

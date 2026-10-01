-- Read-only sanity checks for the Buckwheat family-sync database.
-- Paste into the Supabase SQL editor (Dashboard -> SQL Editor) and run.
-- Nothing here writes, migrates or locks anything.

-- 1. Did all three migrations apply?
--    Expect three rows, installed_rank 1..3, every success = true.
select installed_rank, version, description, type, success
from flyway_schema_history
order by installed_rank;

-- 2. Do the 14 family tables exist?
--    Expect 14 rows. If V1 has not run this returns zero rows.
select table_name
from information_schema.tables
where table_schema = 'public'
  and table_name in (
    'families', 'members', 'invites', 'member_tokens',
    'budget_periods', 'transactions', 'archived_transactions',
    'family_state', 'period_limits', 'saved_categories',
    'saved_tags', 'recurring_templates', 'savings_goals', 'family_settings'
  )
order by table_name;

-- 3. Is row-level security actually on? (migration V3)
--    Expect 14 rows with rowsecurity = true and policies = 0.
--    policies = 0 is intentional: it is what locks out the anon and
--    authenticated REST roles. The server bypasses RLS because it connects as
--    postgres, so this check says nothing about the server's own access.
select c.relname as table_name,
       c.relrowsecurity as rls_enabled,
       (select count(*)
          from pg_policies p
         where p.schemaname = 'public' and p.tablename = c.relname) as policies
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relname in (
    'families', 'members', 'invites', 'member_tokens',
    'budget_periods', 'transactions', 'archived_transactions',
    'family_state', 'period_limits', 'saved_categories',
    'saved_tags', 'recurring_templates', 'savings_goals', 'family_settings'
  )
order by c.relname;

-- 4. Which database role am I, and does it bypass RLS?
--    run_service_role: true for postgres, which is what the server uses.
select current_user,
       session_user,
       rolsuper       as is_superuser,
       rolbypassrls   as bypasses_row_level_security
from pg_roles
where rolname = current_user;

-- 5. Live traffic. Zero rows just means no family has been enrolled yet.
select f.id            as family_id,
       count(distinct m.id)      as members,
       count(distinct t.token_hash) as active_tokens,
       max(t.created_at)         as newest_token
from families f
left join members m       on m.family_id = f.id
left join member_tokens t on t.family_id = f.id
group by f.id
order by f.created_at
limit 50;

-- 6. How far has the shared sync cursor moved?
--    A rising value means devices are exchanging changes; a value pinned at 0
--    after a family has synced points at the wrong cursor on the client.
select last_value as current_seq,
       (select coalesce(max(seq), 0) from transactions) as max_transaction_seq
from sync_sequence;

-- 7. Storage footprint against the 500 MB free-tier ceiling.
select pg_size_pretty(pg_database_size(current_database())) as database_size;
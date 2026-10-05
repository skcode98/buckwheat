-- The governance tables, gone.
--
-- `family_state`, `period_limits` and `spend_assignments` were the pool, the per-member split and
-- head-to-member spend requests. None of them is in the sync contract (`SyncTables.ALL`), so no client
-- has ever written a row through the public API, and with them the owner-only rules that gated them
-- have no surface to gate. `transactions.bucket` and `transactions.assignment_id`, added in V5
-- alongside them, carry no foreign key to any of the three, so the spend history survives the drop.
--
-- Ordered so a reader can see the three are independent: nothing references anything here.

drop table if exists spend_assignments;
drop table if exists period_limits;
drop table if exists family_state;

-- `invites.redeemed_at`, gone too.
--
-- The join code is a durable family credential, not a ticket: the owner persists it and re-shows it to
-- every new member, so it has to admit every join rather than the first one. Nothing writes the column
-- any more, and a `redeemed_at` that is never set is exactly the leftover that lets a later reader
-- assume joining consumes the code and re-caps the family at two.
alter table invites drop column if exists redeemed_at;

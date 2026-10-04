-- Departure is a stamp, not a deletion.
--
-- `members` used to be the ownership column every synced transaction pointed at, so deleting the row
-- on leave silently rewrote the author of that member's whole history to null. Keeping the row means
-- `transactions.member_id` still resolves to a real member id, and `departed_at` is what tells the
-- roster and `SyncStore.authorize` that the row is closed rather than open.
--
-- Nullable and unconstrained: a member written before this migration, or one whose owner never had a
-- reason to leave, has no departure, and that is the same as having never departed. No backfill, so a
-- member who had already left under the old behaviour cannot be recovered -- their row was gone.
alter table members add column if not exists departed_at timestamptz;

-- The roster is read per family on every members call, so the departed flag is worth indexing rather
-- than filtered on the client.
create index if not exists index_members_departed_at on members (departed_at);

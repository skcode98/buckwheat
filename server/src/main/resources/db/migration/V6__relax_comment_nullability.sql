-- The single-table sync contract treats `comment` as an optional wire field, so a payload that omits
-- it binds NULL. V1 declared the column `not null`, which turned an absent comment into a driver error
-- rather than a stored absence.
alter table transactions alter column comment drop not null;
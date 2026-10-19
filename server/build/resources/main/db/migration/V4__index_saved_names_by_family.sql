-- Two members can independently save the same category or tag name and they sync as two distinct
-- rows, so neither name is unique on its own. Index the lookup that survives -- every read is scoped
-- to a family -- without constraining what a family may save twice.
create index if not exists saved_categories_family_id_name_idx
    on saved_categories (family_id, name);

create index if not exists saved_tags_family_id_name_idx
    on saved_tags (family_id, name);
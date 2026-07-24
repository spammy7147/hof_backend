alter table characters
    add column detail_synced_at timestamp with time zone;

update characters c
set detail_synced_at = c.updated_at
where nullif(trim(c.job), '') is not null
   or c.level is not null
   or c.image_url is not null
   or c.pattern_slot_count > 0
   or exists (select 1 from character_stats x where x.character_id = c.id)
   or exists (select 1 from character_status_lines x where x.character_id = c.id)
   or exists (select 1 from character_pattern_slots x where x.character_id = c.id)
   or exists (select 1 from character_action_patterns x where x.character_id = c.id)
   or exists (select 1 from character_guard_settings x where x.character_id = c.id)
   or exists (select 1 from character_position_choices x where x.character_id = c.id)
   or exists (select 1 from character_equipment x where x.character_id = c.id)
   or exists (select 1 from character_skills x where x.character_id = c.id);

create index idx_characters_account_detail_synced
    on characters (account_id, detail_synced_at, id);

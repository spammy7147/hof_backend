alter table character_equipment_candidates add column quantity integer;

alter table character_equipment_candidates add constraint ck_character_equipment_candidates_quantity
    check (quantity is null or quantity >= 0);

alter table character_pattern_options drop constraint ck_character_pattern_options_type;
alter table character_pattern_options add constraint ck_character_pattern_options_type
    check (case option_type
        when 'CONDITION' then true
        when 'SKILL' then true
        when 'CLASS' then true
        else false
    end);

alter table character_operation_jobs drop constraint ck_character_operation_jobs_type;
alter table character_operation_jobs add constraint ck_character_operation_jobs_type
    check (operation_type in ('DEEP_SYNC', 'RESTORE', 'TRANSFER'));

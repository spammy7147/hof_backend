alter table account_battle_map_states
    add column key_mode varchar(20) default 'UNKNOWN' not null;

alter table unresolved_battle_maps
    add column key_mode varchar(20) default 'UNKNOWN' not null;

update account_battle_map_states
set key_mode = 'LIMITED'
where key_count is not null;

update unresolved_battle_maps
set key_mode = 'LIMITED'
where key_count is not null;

alter table account_battle_map_states add constraint ck_account_battle_map_states_key_mode
    check (
        case key_mode
            when 'NOT_REQUIRED' then true
            when 'LIMITED' then true
            when 'UNLIMITED' then true
            when 'UNKNOWN' then true
            else false
        end
    );

alter table account_battle_map_states add constraint ck_account_battle_map_states_key_consistency
    check (
        (key_mode = 'LIMITED' and key_count is not null)
        or (key_mode <> 'LIMITED' and key_count is null)
    );

alter table unresolved_battle_maps add constraint ck_unresolved_battle_maps_key_mode
    check (
        case key_mode
            when 'NOT_REQUIRED' then true
            when 'LIMITED' then true
            when 'UNLIMITED' then true
            when 'UNKNOWN' then true
            else false
        end
    );

alter table unresolved_battle_maps add constraint ck_unresolved_battle_maps_key_consistency
    check (
        (key_mode = 'LIMITED' and key_count is not null)
        or (key_mode <> 'LIMITED' and key_count is null)
    );

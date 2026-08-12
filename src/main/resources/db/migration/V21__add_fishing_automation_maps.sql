create table fishing_automation_maps (
    id bigserial,
    automation_entry_id bigint not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_fishing_automation_maps primary key (id),
    constraint fk_fishing_automation_maps_entry foreign key (automation_entry_id)
        references automation_entries(id) on delete cascade,
    constraint fk_fishing_automation_maps_preset foreign key (party_preset_id)
        references party_presets(id) on delete set null,
    constraint uk_fishing_automation_maps_target unique (automation_entry_id, category_id, map_code),
    constraint ck_fishing_automation_maps_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end),
    constraint ck_fishing_automation_maps_order check (execution_order >= 0)
);

create index idx_fishing_automation_maps_entry_order
    on fishing_automation_maps (automation_entry_id, execution_order, id);

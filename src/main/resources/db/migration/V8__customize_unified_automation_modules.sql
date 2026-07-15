delete from automation_action_runs
where job_id in (
    select jobs.id
    from automation_jobs jobs
    join automation_profiles profiles on profiles.id = jobs.profile_id
    where profiles.mode = 'UNIFIED'
);

delete from automation_jobs
where profile_id in (
    select id
    from automation_profiles
    where mode = 'UNIFIED'
);

delete from automation_profile_maps
where profile_id in (
    select id
    from automation_profiles
    where mode = 'UNIFIED'
);

delete from automation_module_configs
where profile_id in (
    select id
    from automation_profiles
    where mode = 'UNIFIED'
);

alter table automation_module_configs
    drop constraint uk_automation_module_configs_profile_module;

alter table automation_module_configs
    add column display_name varchar(50);

update automation_module_configs
set display_name = module_type;

alter table automation_module_configs
    alter column display_name set not null;

alter table automation_module_configs
    add column threshold_percent integer;

alter table automation_module_configs
    add constraint ck_automation_module_configs_threshold_percent
        check (threshold_percent is null or threshold_percent between 1 and 100);

create table automation_module_legacy_settings (
    module_config_id bigint,
    settings_json text not null,
    constraint pk_automation_module_legacy_settings primary key (module_config_id),
    constraint fk_automation_module_legacy_settings_config foreign key (module_config_id)
        references automation_module_configs (id) on delete cascade
);

insert into automation_module_legacy_settings (module_config_id, settings_json)
select id, settings_json
from automation_module_configs;

alter table automation_module_configs
    drop column settings_json;

create table automation_module_maps (
    id bigserial,
    module_config_id bigint not null,
    battle_map_id bigint not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_automation_module_maps primary key (id),
    constraint fk_automation_module_maps_config foreign key (module_config_id)
        references automation_module_configs (id) on delete cascade,
    constraint fk_automation_module_maps_battle_map foreign key (battle_map_id)
        references battle_maps (id),
    constraint fk_automation_module_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_automation_module_maps_module_map unique (module_config_id, battle_map_id),
    constraint ck_automation_module_maps_execution_order check (execution_order >= 0)
);

create index idx_automation_module_maps_module_order
    on automation_module_maps (module_config_id, execution_order, id);

create index idx_automation_module_maps_battle_map
    on automation_module_maps (battle_map_id, id);

create index idx_automation_module_maps_party_preset
    on automation_module_maps (party_preset_id, id);

create table automation_module_quests (
    id bigserial,
    module_config_id bigint not null,
    quest_code varchar(100) not null,
    execution_order integer not null,
    constraint pk_automation_module_quests primary key (id),
    constraint fk_automation_module_quests_config foreign key (module_config_id)
        references automation_module_configs (id) on delete cascade,
    constraint uk_automation_module_quests_module_quest unique (module_config_id, quest_code),
    constraint ck_automation_module_quests_execution_order check (execution_order >= 0)
);

create index idx_automation_module_quests_module_order
    on automation_module_quests (module_config_id, execution_order, id);

create table automation_module_quest_maps (
    id bigserial,
    module_quest_id bigint not null,
    battle_map_id bigint not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_automation_module_quest_maps primary key (id),
    constraint fk_automation_module_quest_maps_quest foreign key (module_quest_id)
        references automation_module_quests (id) on delete cascade,
    constraint fk_automation_module_quest_maps_battle_map foreign key (battle_map_id)
        references battle_maps (id),
    constraint fk_automation_module_quest_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_automation_module_quest_maps_quest_map unique (module_quest_id, battle_map_id),
    constraint ck_automation_module_quest_maps_execution_order check (execution_order >= 0)
);

create index idx_automation_module_quest_maps_quest_order
    on automation_module_quest_maps (module_quest_id, execution_order, id);

create index idx_automation_module_quest_maps_battle_map
    on automation_module_quest_maps (battle_map_id, id);

create index idx_automation_module_quest_maps_party_preset
    on automation_module_quest_maps (party_preset_id, id);

alter table automation_jobs
    add column current_module_config_id bigint;

alter table automation_jobs
    add constraint fk_automation_jobs_current_module_config foreign key (current_module_config_id)
        references automation_module_configs (id) on delete set null;

create index idx_automation_jobs_current_module_config
    on automation_jobs (current_module_config_id, id);

alter table automation_action_runs
    add column module_config_id bigint;

alter table automation_action_runs
    add constraint fk_automation_action_runs_module_config foreign key (module_config_id)
        references automation_module_configs (id) on delete set null;

create index idx_automation_action_runs_module_config
    on automation_action_runs (module_config_id, id);

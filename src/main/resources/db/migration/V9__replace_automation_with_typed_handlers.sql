delete from automation_action_runs;
delete from automation_jobs;
delete from automation_outbox;
delete from automation_consumed_events;
delete from account_automation_leases;
delete from automation_module_quest_maps;
delete from automation_module_quests;
delete from automation_module_maps;
delete from automation_module_legacy_settings;
delete from automation_module_configs;
delete from automation_profile_maps;
delete from automation_profiles;

alter table party_presets
    add column is_primary boolean not null default false;

-- PostgreSQL partial indexes are not accepted by H2 in PostgreSQL mode. A nullable marker preserves
-- unlimited non-primary presets while a portable unique constraint permits only one primary/account.
alter table party_presets
    add column primary_marker integer;

alter table party_presets
    add constraint ck_party_presets_primary_marker
        check ((is_primary and primary_marker = 1) or (not is_primary and primary_marker is null));

alter table party_presets
    add constraint uk_party_presets_account_primary_marker unique (account_id, primary_marker);

create table automation_entries (
    id bigserial,
    account_id bigint not null,
    automation_type varchar(30) not null,
    priority integer not null,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_automation_entries primary key (id),
    constraint fk_automation_entries_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_automation_entries_account_type unique (account_id, automation_type),
    constraint ck_automation_entries_type
        check (case automation_type
            when 'QUEST' then true
            when 'BATTLE_MAP' then true
            when 'ADVENTURE_MAP' then true
            else false
        end),
    constraint ck_automation_entries_priority check (priority >= 0)
);

create index idx_automation_entries_account_priority
    on automation_entries (account_id, priority, id);

create table quest_automation_selections (
    id bigserial,
    automation_entry_id bigint not null,
    quest_code varchar(100) not null,
    enabled boolean not null,
    source_order integer not null,
    constraint pk_quest_automation_selections primary key (id),
    constraint fk_quest_automation_selections_entry foreign key (automation_entry_id)
        references automation_entries (id) on delete cascade,
    constraint uk_quest_automation_selections_entry_quest unique (automation_entry_id, quest_code),
    constraint ck_quest_automation_selections_source_order check (source_order >= 0)
);

create index idx_quest_automation_selections_entry_order
    on quest_automation_selections (automation_entry_id, source_order, id);

create table quest_automation_maps (
    id bigserial,
    quest_selection_id bigint not null,
    mission_key varchar(100) not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    execution_order integer not null,
    manually_overridden boolean not null,
    constraint pk_quest_automation_maps primary key (id),
    constraint fk_quest_automation_maps_selection foreign key (quest_selection_id)
        references quest_automation_selections (id) on delete cascade,
    constraint fk_quest_automation_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_quest_automation_maps_selection_mission_map
        unique (quest_selection_id, mission_key, category_id, map_code),
    constraint ck_quest_automation_maps_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end),
    constraint ck_quest_automation_maps_execution_order check (execution_order >= 0)
);

create index idx_quest_automation_maps_selection_order
    on quest_automation_maps (quest_selection_id, execution_order, id);
create index idx_quest_automation_maps_identity
    on quest_automation_maps (category_id, map_code, id);
create index idx_quest_automation_maps_party_preset
    on quest_automation_maps (party_preset_id, id);

create table quest_map_execution_counters (
    id bigserial,
    account_id bigint not null,
    quest_code varchar(100) not null,
    quest_cycle varchar(100) not null,
    mission_key varchar(100) not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    successful_runs integer not null,
    constraint pk_quest_map_execution_counters primary key (id),
    constraint fk_quest_map_execution_counters_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_quest_map_execution_counters_identity
        unique (account_id, quest_code, quest_cycle, mission_key, category_id, map_code),
    constraint ck_quest_map_execution_counters_successful_runs check (successful_runs >= 0)
);

create index idx_quest_map_execution_counters_account_cycle
    on quest_map_execution_counters (account_id, quest_cycle, quest_code, id);
create index idx_quest_map_execution_counters_map_identity
    on quest_map_execution_counters (category_id, map_code, id);

create table battle_automation_maps (
    id bigserial,
    automation_entry_id bigint not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    daily_target_count integer not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_battle_automation_maps primary key (id),
    constraint fk_battle_automation_maps_entry foreign key (automation_entry_id)
        references automation_entries (id) on delete cascade,
    constraint fk_battle_automation_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_battle_automation_maps_entry_map unique (automation_entry_id, category_id, map_code),
    constraint ck_battle_automation_maps_daily_target check (daily_target_count > 0),
    constraint ck_battle_automation_maps_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end),
    constraint ck_battle_automation_maps_execution_order check (execution_order >= 0)
);

create index idx_battle_automation_maps_entry_order
    on battle_automation_maps (automation_entry_id, execution_order, id);
create index idx_battle_automation_maps_identity
    on battle_automation_maps (category_id, map_code, id);
create index idx_battle_automation_maps_party_preset
    on battle_automation_maps (party_preset_id, id);

create table battle_automation_daily_progress (
    id bigserial,
    account_id bigint not null,
    progress_date date not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    source varchar(50) not null,
    successful_runs integer not null,
    updated_at timestamp with time zone not null,
    constraint pk_battle_automation_daily_progress primary key (id),
    constraint fk_battle_automation_daily_progress_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_battle_automation_daily_progress_identity
        unique (account_id, progress_date, category_id, map_code, source),
    constraint ck_battle_automation_daily_progress_successful_runs check (successful_runs >= 0)
);

create index idx_battle_automation_daily_progress_account_date
    on battle_automation_daily_progress (account_id, progress_date, source, id);
create index idx_battle_automation_daily_progress_map_identity
    on battle_automation_daily_progress (category_id, map_code, progress_date, id);

create table adventure_automation_maps (
    id bigserial,
    automation_entry_id bigint not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_adventure_automation_maps primary key (id),
    constraint fk_adventure_automation_maps_entry foreign key (automation_entry_id)
        references automation_entries (id) on delete cascade,
    constraint fk_adventure_automation_maps_party_preset foreign key (party_preset_id)
        references party_presets (id) on delete set null,
    constraint uk_adventure_automation_maps_entry_map unique (automation_entry_id, category_id, map_code),
    constraint ck_adventure_automation_maps_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end),
    constraint ck_adventure_automation_maps_execution_order check (execution_order >= 0)
);

create index idx_adventure_automation_maps_entry_order
    on adventure_automation_maps (automation_entry_id, execution_order, id);
create index idx_adventure_automation_maps_identity
    on adventure_automation_maps (category_id, map_code, id);
create index idx_adventure_automation_maps_party_preset
    on adventure_automation_maps (party_preset_id, id);

create table adventure_daily_refresh (
    id bigserial,
    account_id bigint not null,
    refresh_date date not null,
    refreshed_at timestamp with time zone not null,
    constraint pk_adventure_daily_refresh primary key (id),
    constraint fk_adventure_daily_refresh_account foreign key (account_id)
        references hof_accounts (id) on delete cascade,
    constraint uk_adventure_daily_refresh_account_date unique (account_id, refresh_date)
);

create index idx_adventure_daily_refresh_account_date
    on adventure_daily_refresh (account_id, refresh_date, id);

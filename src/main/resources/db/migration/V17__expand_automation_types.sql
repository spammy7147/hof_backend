delete from battle_automation_maps
where category_id = 'raid';

alter table automation_entries
    drop constraint ck_automation_entries_type;

alter table automation_entries
    add constraint ck_automation_entries_type
        check (case automation_type
            when 'QUEST' then true
            when 'BATTLE_MAP' then true
            when 'ADVENTURE_MAP' then true
            when 'RAID' then true
            when 'UNION' then true
            when 'FISHING' then true
            else false
        end);

alter table automation_work_sessions drop constraint ck_automation_work_type;
alter table automation_work_sessions add constraint ck_automation_work_type
    check (position(',' || work_type || ',' in ',QUEST,BATTLE_MAP,ADVENTURE_MAP,RAID,UNION,FISHING,') > 0);

create table union_automation_maps (
    id bigserial,
    automation_entry_id bigint not null,
    category_id varchar(50) not null,
    map_code varchar(100) not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_union_automation_maps primary key (id),
    constraint fk_union_automation_maps_entry foreign key (automation_entry_id)
        references automation_entries(id) on delete cascade,
    constraint fk_union_automation_maps_preset foreign key (party_preset_id)
        references party_presets(id) on delete set null,
    constraint uk_union_automation_maps_target unique (automation_entry_id, category_id, map_code),
    constraint ck_union_automation_maps_category check (category_id = 'union'),
    constraint ck_union_automation_maps_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end),
    constraint ck_union_automation_maps_order check (execution_order >= 0)
);

create index idx_union_automation_maps_entry_order
    on union_automation_maps (automation_entry_id, execution_order, id);

create table raid_automation_targets (
    id bigserial,
    automation_entry_id bigint not null,
    raid_id varchar(200) not null,
    display_name varchar(255) not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    execution_order integer not null,
    constraint pk_raid_automation_targets primary key (id),
    constraint fk_raid_automation_targets_entry foreign key (automation_entry_id)
        references automation_entries(id) on delete cascade,
    constraint fk_raid_automation_targets_preset foreign key (party_preset_id)
        references party_presets(id) on delete set null,
    constraint uk_raid_automation_targets_target unique (automation_entry_id, raid_id),
    constraint ck_raid_automation_targets_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end),
    constraint ck_raid_automation_targets_order check (execution_order >= 0)
);

create index idx_raid_automation_targets_entry_order
    on raid_automation_targets (automation_entry_id, execution_order, id);

create table fishing_automation_settings (
    id bigserial,
    automation_entry_id bigint not null,
    preset_mode varchar(20) not null,
    party_preset_id bigint,
    constraint pk_fishing_automation_settings primary key (id),
    constraint fk_fishing_automation_settings_entry foreign key (automation_entry_id)
        references automation_entries(id) on delete cascade,
    constraint fk_fishing_automation_settings_preset foreign key (party_preset_id)
        references party_presets(id) on delete set null,
    constraint uk_fishing_automation_settings_entry unique (automation_entry_id),
    constraint ck_fishing_automation_settings_preset_mode
        check (case preset_mode when 'PRIMARY' then true when 'EXPLICIT' then true else false end)
);

create table automation_rotation_states (
    id bigserial,
    automation_entry_id bigint not null,
    current_target_key varchar(255) not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint pk_automation_rotation_states primary key (id),
    constraint fk_automation_rotation_states_entry foreign key (automation_entry_id)
        references automation_entries(id) on delete cascade,
    constraint uk_automation_rotation_states_entry unique (automation_entry_id)
);

create table raid_automation_cycles (
    id bigserial,
    account_id bigint not null,
    automation_entry_id bigint,
    raid_id varchar(200) not null,
    raid_name varchar(255) not null,
    status varchar(32) not null,
    last_observed_status varchar(32),
    next_check_at timestamp with time zone,
    open_marker integer,
    started_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    version bigint not null default 0,
    constraint pk_raid_automation_cycles primary key (id),
    constraint fk_raid_automation_cycles_account foreign key (account_id)
        references hof_accounts(id) on delete cascade,
    constraint fk_raid_automation_cycles_entry foreign key (automation_entry_id)
        references automation_entries(id) on delete set null,
    constraint uk_raid_automation_cycles_open unique (account_id, open_marker),
    constraint ck_raid_automation_cycles_status
        check (position(',' || status || ',' in ',REGISTERED_WAITING,IN_BATTLE,REWARD_PENDING,COMPLETED,ABORTED_CLOSED,') > 0),
    constraint ck_raid_automation_cycles_open
        check (
            (position(',' || status || ',' in ',COMPLETED,ABORTED_CLOSED,') > 0 and open_marker is null and finished_at is not null)
            or
            (position(',' || status || ',' in ',REGISTERED_WAITING,IN_BATTLE,REWARD_PENDING,') > 0 and open_marker = 1 and finished_at is null)
        )
);

create index idx_raid_automation_cycles_account_started
    on raid_automation_cycles (account_id, started_at, id);

create index idx_raid_automation_cycles_due
    on raid_automation_cycles (open_marker, next_check_at, account_id, id);

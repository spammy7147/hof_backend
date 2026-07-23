create table automation_work_sessions (
    id bigserial,
    account_id bigint not null,
    automation_entry_id bigint not null,
    work_type varchar(24) not null,
    target_key varchar(255) not null,
    status varchar(24) not null,
    config_version varchar(64) not null,
    target_count integer,
    confirmed_count integer not null default 0,
    quest_cycle varchar(64),
    mission_key varchar(255),
    mission_type varchar(32),
    observed_current integer,
    observed_required integer,
    material_name varchar(255),
    material_missing integer,
    next_check_at timestamp with time zone,
    last_verified_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    version bigint not null default 0,
    constraint pk_automation_work_sessions primary key (id),
    constraint fk_automation_work_account foreign key (account_id)
        references hof_accounts(id) on delete cascade,
    constraint fk_automation_work_entry foreign key (automation_entry_id)
        references automation_entries(id) on delete cascade,
    constraint ck_automation_work_type
        check (position(',' || work_type || ',' in ',QUEST,BATTLE_MAP,ADVENTURE_MAP,') > 0),
    constraint ck_automation_work_status
        check (position(',' || status || ',' in ',RUNNING,WAITING_COOLDOWN,WAITING_RESOURCE,YIELDED_PRIORITY,COMPLETED,STOPPED,') > 0),
    constraint ck_automation_work_count
        check (confirmed_count >= 0 and (target_count is null or target_count > 0)),
    constraint ck_automation_work_material_missing
        check (material_missing is null or material_missing >= 0),
    constraint ck_automation_work_finished
        check ((position(',' || status || ',' in ',COMPLETED,STOPPED,') > 0 and finished_at is not null) or
               (position(',' || status || ',' in ',COMPLETED,STOPPED,') = 0 and finished_at is null))
);

create index idx_automation_work_account_status
    on automation_work_sessions (account_id, status, updated_at, id);

create index idx_automation_work_due
    on automation_work_sessions (status, next_check_at, account_id, id);

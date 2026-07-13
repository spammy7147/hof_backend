alter table automation_jobs
    add column current_module varchar(50);
alter table automation_jobs
    add column current_action varchar(255);
alter table automation_jobs
    add column next_run_at timestamp with time zone;
alter table automation_jobs
    add column last_heartbeat_at timestamp with time zone;
alter table automation_jobs
    add column version bigint not null default 0;

alter table automation_profile_maps
    add column module_type varchar(50) not null default 'NORMAL_MAP';
alter table automation_profile_maps
    add column purpose varchar(50) not null default 'PRIMARY';

create table automation_module_configs (
    id bigserial,
    profile_id bigint not null,
    module_type varchar(50) not null,
    enabled boolean not null,
    priority integer not null,
    settings_json text not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint pk_automation_module_configs primary key (id),
    constraint fk_automation_module_configs_profile foreign key (profile_id)
        references automation_profiles (id) on delete cascade,
    constraint uk_automation_module_configs_profile_module unique (profile_id, module_type),
    constraint ck_automation_module_configs_priority check (priority >= 0)
);

create index idx_automation_module_configs_profile_priority
    on automation_module_configs (profile_id, priority, id);

create table automation_action_runs (
    id bigserial,
    job_id bigint not null,
    module_type varchar(50) not null,
    action_type varchar(80) not null,
    action_key varchar(255),
    status varchar(50) not null,
    request_key varchar(255) not null,
    payload_json text not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    constraint pk_automation_action_runs primary key (id),
    constraint fk_automation_action_runs_job foreign key (job_id)
        references automation_jobs (id) on delete cascade,
    constraint uk_automation_action_runs_request_key unique (request_key),
    constraint ck_automation_action_runs_attempt_count check (attempt_count >= 0)
);

create index idx_automation_action_runs_job_status_updated
    on automation_action_runs (job_id, status, updated_at, id);

create index idx_automation_jobs_recovery
    on automation_jobs (status, next_run_at, last_heartbeat_at, id);

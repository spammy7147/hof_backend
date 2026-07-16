create table typed_automation_runtime_states (
    account_id bigint,
    lifecycle_status varchar(20) not null,
    stop_reason varchar(30),
    retry_attempt integer not null default 0,
    next_attempt_at timestamp with time zone,
    lease_token varchar(128),
    lease_until timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint pk_typed_automation_runtime_states primary key (account_id),
    constraint fk_typed_runtime_account foreign key (account_id) references hof_accounts(id) on delete cascade,
    constraint ck_typed_runtime_lifecycle check (lifecycle_status in ('RUNNING','PAUSED','STOPPED')),
    constraint ck_typed_runtime_stop check (
        (lifecycle_status = 'STOPPED' and stop_reason is not null) or
        (lifecycle_status <> 'STOPPED' and stop_reason is null)
    ),
    constraint ck_typed_runtime_retry check (retry_attempt >= 0),
    constraint ck_typed_runtime_lease check (
        (lease_token is null and lease_until is null) or (lease_token is not null and lease_until is not null)
    )
);

create table typed_automation_action_runs (
    id bigserial,
    account_id bigint not null,
    automation_entry_id bigint not null,
    execution_identity varchar(128) not null,
    action_kind varchar(30) not null,
    schema_version integer not null,
    payload_json text not null,
    action_fingerprint varchar(64) not null,
    status varchar(20) not null,
    retry_attempt integer not null default 0,
    next_attempt_at timestamp with time zone,
    lease_token varchar(128) not null,
    last_error text,
    created_at timestamp with time zone not null,
    submitted_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    constraint pk_typed_automation_action_runs primary key (id),
    constraint fk_typed_action_account foreign key (account_id) references hof_accounts(id) on delete cascade,
    constraint fk_typed_action_entry foreign key (automation_entry_id) references automation_entries(id) on delete cascade,
    constraint uk_typed_action_execution unique (account_id, execution_identity),
    constraint ck_typed_action_status check (status in ('PREPARED','SUBMITTING','SUCCEEDED','FAILED','AMBIGUOUS')),
    constraint ck_typed_action_schema check (schema_version > 0),
    constraint ck_typed_action_retry check (retry_attempt >= 0),
    constraint ck_typed_action_fingerprint check (length(action_fingerprint) = 64)
);

create index idx_typed_action_account_status on typed_automation_action_runs(account_id, status, updated_at, id);

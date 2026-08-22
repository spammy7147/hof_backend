create table automation_action_attempts (
    id bigserial,
    account_id bigint not null,
    automation_entry_id bigint,
    execution_identity varchar(128) not null,
    action_kind varchar(50) not null,
    scope_kind varchar(50) not null,
    scope_key varchar(200) not null,
    policy_version varchar(80) not null,
    baseline_fingerprint varchar(128) not null,
    created_at timestamp with time zone not null,
    submitted_at timestamp with time zone,
    constraint pk_automation_action_attempts primary key (id),
    constraint fk_automation_action_attempt_account foreign key (account_id)
        references hof_accounts(id) on delete cascade,
    constraint fk_automation_action_attempt_entry foreign key (automation_entry_id)
        references automation_entries(id) on delete set null,
    constraint uk_automation_action_attempt_execution unique (account_id, execution_identity),
    constraint ck_automation_action_attempt_scope_key check (char_length(trim(scope_key)) between 1 and 200),
    constraint ck_automation_action_attempt_baseline check (char_length(trim(baseline_fingerprint)) between 1 and 128)
);

create table automation_action_convergences (
    id bigserial,
    attempt_id bigint not null,
    account_id bigint not null,
    scope_kind varchar(50) not null,
    scope_key varchar(200) not null,
    result varchar(30),
    active_marker integer,
    successful_observation_count integer not null default 0,
    first_pending_at timestamp with time zone,
    next_probe_at timestamp with time zone,
    reason_code varchar(100),
    evidence_case_id varchar(64),
    suppression_released_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint pk_automation_action_convergences primary key (id),
    constraint fk_automation_action_convergence_attempt foreign key (attempt_id)
        references automation_action_attempts(id) on delete cascade,
    constraint fk_automation_action_convergence_account foreign key (account_id)
        references hof_accounts(id) on delete cascade,
    constraint uk_automation_action_convergence_attempt unique (attempt_id),
    constraint uk_automation_action_convergence_active_scope unique (account_id, scope_kind, scope_key, active_marker),
    constraint ck_automation_action_convergence_result check (
        result is null or position(',' || result || ',' in ',APPLIED,NOT_APPLIED,SUPERSEDED,PENDING,HELD,RESULT_UNOBSERVED,') > 0
    ),
    constraint ck_automation_action_convergence_active check (
        (result is null and active_marker = 1 and finished_at is null)
        or (result = 'PENDING' and active_marker = 1 and finished_at is null)
        or (result is not null and result <> 'PENDING' and active_marker is null and finished_at is not null)
    ),
    constraint ck_automation_action_convergence_observations check (successful_observation_count >= 0)
);

create index idx_automation_action_convergence_due
    on automation_action_convergences(account_id, active_marker, next_probe_at, id);

create table automation_account_battle_gates (
    account_id bigint,
    challenge_id bigint,
    reason varchar(100) not null,
    opened_at timestamp with time zone not null,
    resolved_at timestamp with time zone,
    version bigint not null default 0,
    constraint pk_automation_account_battle_gates primary key (account_id),
    constraint fk_automation_account_battle_gate_account foreign key (account_id)
        references hof_accounts(id) on delete cascade
);

create table automation_evidence_cases (
    id varchar(64),
    attempt_id bigint not null,
    evidence_source varchar(40) not null,
    observation_completeness varchar(30),
    observation_freshness varchar(20),
    state_fingerprint varchar(128),
    response_shape_fingerprint varchar(128),
    sanitized_snippet varchar(1000),
    reason_code varchar(100) not null,
    policy_version varchar(80) not null,
    build_version varchar(80) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    constraint pk_automation_evidence_cases primary key (id),
    constraint fk_automation_evidence_case_attempt foreign key (attempt_id)
        references automation_action_attempts(id) on delete cascade
);

create index idx_automation_evidence_cases_expiry on automation_evidence_cases(expires_at, id);

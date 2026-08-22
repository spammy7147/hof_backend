alter table automation_action_attempts
    add column observation_only boolean not null default false;

update automation_action_convergences
set result = 'PENDING',
    first_pending_at = coalesce(first_pending_at, updated_at),
    next_probe_at = coalesce(next_probe_at, updated_at),
    reason_code = coalesce(reason_code, 'ORPHAN_RESULT_RECONCILED')
where result is null;

update automation_action_convergences
set result = 'SUPERSEDED',
    active_marker = null,
    next_probe_at = null,
    reason_code = 'AUTOMATION_ENTRY_DELETED',
    finished_at = updated_at
where active_marker = 1
  and attempt_id in (
      select id from automation_action_attempts where automation_entry_id is null
  );

alter table automation_action_convergences
    drop constraint ck_automation_action_convergence_active;

alter table automation_action_convergences
    alter column result set not null;

alter table automation_action_convergences
    add constraint ck_automation_action_convergence_active check (
        (result = 'PENDING' and active_marker = 1 and finished_at is null)
        or (result <> 'PENDING' and active_marker is null and finished_at is not null)
    );

create table automation_convergence_shadow_evaluations (
    id varchar(64),
    account_id bigint not null,
    execution_identity_hash varchar(64) not null,
    action_kind varchar(50) not null,
    scope_kind varchar(50) not null,
    scope_key_hash varchar(64) not null,
    evidence_kind varchar(50) not null,
    evidence_completeness varchar(40) not null,
    response_shape_fingerprint varchar(64) not null,
    sanitized_snippet varchar(1000) not null,
    legacy_decision varchar(40) not null,
    legacy_reason_code varchar(100) not null,
    new_result varchar(30) not null,
    new_reason_code varchar(100) not null,
    result_differs boolean not null,
    reason_differs boolean not null,
    shape_differs boolean not null,
    completeness_differs boolean not null,
    policy_version varchar(80) not null,
    build_version varchar(80) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    constraint pk_automation_convergence_shadow_evaluations primary key (id),
    constraint fk_automation_convergence_shadow_account foreign key (account_id)
        references hof_accounts(id) on delete cascade
);

create index idx_automation_convergence_shadow_gate
    on automation_convergence_shadow_evaluations(
        created_at, action_kind, result_differs, reason_differs, shape_differs, completeness_differs
    );

create index idx_automation_convergence_shadow_expiry
    on automation_convergence_shadow_evaluations(expires_at, id);

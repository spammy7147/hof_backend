update automation_work_sessions work
set status = 'COMPLETED',
    next_check_at = null,
    finished_at = current_timestamp,
    updated_at = current_timestamp
where work.work_type = 'BATTLE_MAP'
  and exists (
      select 1
      from typed_automation_action_runs stored_action
      where stored_action.account_id = work.account_id
        and stored_action.automation_entry_id = work.automation_entry_id
        and stored_action.action_kind = 'BATTLE_MAP'
        and stored_action.status in ('SUBMITTING', 'RECONCILING')
        and work.id = (
            select max(candidate.id)
            from automation_work_sessions candidate
            where candidate.account_id = stored_action.account_id
              and candidate.automation_entry_id = stored_action.automation_entry_id
              and candidate.work_type = 'BATTLE_MAP'
              and candidate.created_at <= stored_action.created_at
        )
  );

update typed_automation_runtime_states
set lifecycle_status = 'PAUSED',
    stop_reason = null,
    stop_action_id = null,
    retry_attempt = 0,
    next_attempt_at = null,
    wait_reason = null,
    lease_token = null,
    lease_until = null,
    warning_text = null,
    last_error = null,
    updated_at = current_timestamp
where last_error = 'Stored typed action integrity check failed.';

update typed_automation_runtime_states runtime
set stop_action_id = null,
    retry_attempt = 0,
    next_attempt_at = null,
    wait_reason = null,
    lease_token = null,
    lease_until = null,
    updated_at = current_timestamp
where exists (
    select 1
    from typed_automation_action_runs stored_action
    where stored_action.account_id = runtime.account_id
);

delete from typed_automation_action_runs;

alter table typed_automation_action_runs drop constraint ck_typed_action_schema;
alter table typed_automation_action_runs drop column schema_version;

update typed_automation_runtime_states
set lifecycle_status = 'RUNNING',
    stop_action_id = null,
    retry_attempt = 0,
    next_attempt_at = current_timestamp,
    wait_reason = 'HOF_CONNECTION',
    lease_token = null,
    lease_until = null,
    updated_at = current_timestamp
where lifecycle_status = 'STOPPED'
  and stop_reason <> 'MANUAL_STOP';

update adventure_daily_preflight_states
set stop_reason = null,
    failed_attempts = 0,
    next_attempt_at = null,
    in_flight_token = null,
    in_flight_until = null,
    updated_at = current_timestamp
where stop_reason is not null;

alter table typed_automation_runtime_states drop constraint ck_typed_runtime_stop;
alter table typed_automation_runtime_states add constraint ck_typed_runtime_stop
    check (
        (lifecycle_status = 'STOPPED' and stop_reason is not null) or
        (lifecycle_status <> 'STOPPED' and (stop_reason is null or stop_reason <> 'MANUAL_STOP'))
    );

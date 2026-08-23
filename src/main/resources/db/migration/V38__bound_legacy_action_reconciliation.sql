alter table typed_automation_action_runs
    add column reconciliation_observation_count integer not null default 0;

alter table typed_automation_action_runs
    add column reconciliation_first_pending_at timestamp with time zone;

update typed_automation_action_runs
set reconciliation_observation_count = 0,
    reconciliation_first_pending_at = coalesce(submitted_at, updated_at, created_at)
where status = 'RECONCILING';

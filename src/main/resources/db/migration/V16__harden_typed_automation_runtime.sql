alter table typed_automation_runtime_states add column warning_text text;
alter table typed_automation_runtime_states add column last_error text;

-- H2 2.4 rejects valid VARCHAR rows against its migrated IN predicate in PostgreSQL mode.
-- SQL-standard POSITION preserves the same exact enum invariant on both H2 and PostgreSQL.
alter table typed_automation_runtime_states drop constraint ck_typed_runtime_lifecycle;
alter table typed_automation_runtime_states add constraint ck_typed_runtime_lifecycle
    check (position(',' || lifecycle_status || ',' in ',RUNNING,PAUSED,STOPPED,') > 0);

alter table typed_automation_action_runs drop constraint ck_typed_action_status;
alter table typed_automation_action_runs add constraint ck_typed_action_status
    check (position(',' || status || ',' in ',PREPARED,SUBMITTING,SUCCEEDED,FAILED,AMBIGUOUS,') > 0);

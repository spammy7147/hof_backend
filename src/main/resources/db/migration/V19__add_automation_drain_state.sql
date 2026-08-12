alter table typed_automation_runtime_states
    add column requested_lifecycle varchar(20);

alter table typed_automation_runtime_states drop constraint ck_typed_runtime_lifecycle;
alter table typed_automation_runtime_states add constraint ck_typed_runtime_lifecycle
    check (position(',' || lifecycle_status || ',' in ',RUNNING,DRAINING,PAUSED,STOPPED,') > 0);

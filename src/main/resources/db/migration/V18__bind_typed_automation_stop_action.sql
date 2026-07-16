alter table typed_automation_runtime_states add column stop_action_id bigint;

alter table typed_automation_runtime_states add constraint fk_typed_runtime_stop_action
    foreign key (stop_action_id) references typed_automation_action_runs(id) on delete set null;

alter table typed_automation_runtime_states add constraint ck_typed_runtime_stop_action
    check (lifecycle_status = 'STOPPED' or stop_action_id is null);

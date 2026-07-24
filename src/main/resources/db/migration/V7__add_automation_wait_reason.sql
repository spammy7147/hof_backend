alter table typed_automation_runtime_states
    add column wait_reason varchar(30);

alter table typed_automation_runtime_states
    add constraint ck_typed_runtime_wait_reason
    check (
        wait_reason is null
        or position(',' || wait_reason || ',' in ',SCHEDULED,HOF_CONNECTION,') > 0
    );

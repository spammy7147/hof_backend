ALTER TABLE typed_automation_runtime_states DROP CONSTRAINT ck_typed_runtime_wait_reason;
ALTER TABLE typed_automation_runtime_states ADD CONSTRAINT ck_typed_runtime_wait_reason
    CHECK (wait_reason IS NULL OR position(',' || wait_reason || ',' in ',SCHEDULED,HOF_CONNECTION,LOOP_INTERVAL,') > 0);

alter table typed_automation_action_runs drop constraint ck_typed_action_status;

alter table typed_automation_action_runs add constraint ck_typed_action_status
    check (position(',' || status || ',' in ',PREPARED,SUBMITTING,RECONCILING,SUCCEEDED,FAILED,AMBIGUOUS,') > 0);

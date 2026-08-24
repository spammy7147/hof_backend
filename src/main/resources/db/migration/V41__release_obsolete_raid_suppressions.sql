update automation_action_convergences
set suppression_released_at = (
    select min(action_run.finished_at)
    from automation_action_attempts attempt
    join typed_automation_action_runs action_run
      on action_run.account_id = attempt.account_id
     and action_run.automation_entry_id = attempt.automation_entry_id
    where attempt.id = automation_action_convergences.attempt_id
      and action_run.action_kind = 'RAID_TOWN'
      and action_run.status = 'SUCCEEDED'
      and action_run.finished_at > automation_action_convergences.finished_at
      and (
          action_run.payload_json like ('%"targetRaidId":"' || automation_action_convergences.scope_key || '"%')
          or action_run.payload_json like ('%"raidId":"' || automation_action_convergences.scope_key || '"%')
      )
)
where suppression_released_at is null
  and result in ('HELD', 'RESULT_UNOBSERVED')
  and scope_kind = 'RAID_ENTRY'
  and exists (
      select 1
      from automation_action_attempts attempt
      join typed_automation_action_runs action_run
        on action_run.account_id = attempt.account_id
       and action_run.automation_entry_id = attempt.automation_entry_id
      where attempt.id = automation_action_convergences.attempt_id
        and action_run.action_kind = 'RAID_TOWN'
        and action_run.status = 'SUCCEEDED'
        and action_run.finished_at > automation_action_convergences.finished_at
        and (
            action_run.payload_json like ('%"targetRaidId":"' || automation_action_convergences.scope_key || '"%')
            or action_run.payload_json like ('%"raidId":"' || automation_action_convergences.scope_key || '"%')
        )
  );

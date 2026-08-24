merge into automation_action_convergences convergence
using (
    select candidate.id,
           min(action_run.finished_at) as released_at
    from automation_action_convergences candidate
    join automation_action_attempts attempt
      on attempt.id = candidate.attempt_id
    join typed_automation_action_runs action_run
      on action_run.account_id = attempt.account_id
     and action_run.automation_entry_id = attempt.automation_entry_id
    where candidate.suppression_released_at is null
      and candidate.result in ('HELD', 'RESULT_UNOBSERVED')
      and candidate.scope_kind = 'RAID_ENTRY'
      and action_run.status = 'SUCCEEDED'
      and action_run.finished_at > candidate.finished_at
      -- payload_json is canonical backend serializer output; POSITION keeps exact tokens portable across PostgreSQL and H2.
      and (
          (
              action_run.action_kind = 'RAID_TOWN'
              and (
                  position(
                      ('"targetRaidId":"' || candidate.scope_key || '"')
                      in action_run.payload_json
                  ) > 0
                  or position(
                      ('"raidId":"' || candidate.scope_key || '"')
                      in action_run.payload_json
                  ) > 0
              )
          )
          or (
              action_run.action_kind = 'BATTLE_MAP'
              and position('"source":"RAID_AUTOMATION"' in action_run.payload_json) > 0
              and position(
                  ('"sourceTargetKey":"' || candidate.scope_key || '"')
                  in action_run.payload_json
              ) > 0
          )
      )
    group by candidate.id
) obsolete
on convergence.id = obsolete.id
when matched then update
set suppression_released_at = obsolete.released_at;

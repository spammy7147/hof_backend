alter table automation_rotation_states add column last_completed_action_id bigint;

-- 기존 순환 위치를 유지하면서 이미 완료된 유니온 행동까지의 순서를 보존한다.
-- 행동 이력 정리 뒤에도 순서를 유지하므로 이 값은 FK로 묶지 않는다.
update automation_rotation_states
set last_completed_action_id = (
    select max(a.id)
    from typed_automation_action_runs a
    where a.automation_entry_id = automation_rotation_states.automation_entry_id
      and a.action_kind = 'BATTLE_MAP'
      and a.status = 'SUCCEEDED'
)
where automation_entry_id in (
    select id from automation_entries where automation_type = 'UNION'
);

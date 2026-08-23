update automation_work_sessions
set status = 'YIELDED_PRIORITY',
    updated_at = current_timestamp
where status = 'RUNNING'
  and id <> (
      select max(candidate.id)
      from automation_work_sessions candidate
      where candidate.account_id = automation_work_sessions.account_id
        and candidate.status = 'RUNNING'
  );

alter table automation_work_sessions
    add column running_slot integer;

update automation_work_sessions
set running_slot = case when status = 'RUNNING' then 1 else null end;

alter table automation_work_sessions
    add constraint ck_automation_work_running_slot
        check (
            (status = 'RUNNING' and running_slot = 1) or
            (status <> 'RUNNING' and running_slot is null)
        );

create unique index ux_automation_work_running_slot
    on automation_work_sessions (account_id, running_slot);

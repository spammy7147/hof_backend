update automation_entries
set enabled = false, updated_at = current_timestamp
where automation_type = 'QUEST';

update automation_work_sessions
set status = 'STOPPED',
    next_check_at = null,
    finished_at = current_timestamp,
    updated_at = current_timestamp
where work_type = 'QUEST'
  and status in ('RUNNING', 'WAITING_COOLDOWN', 'WAITING_RESOURCE', 'YIELDED_PRIORITY');

update typed_automation_action_runs
set status = 'FAILED',
    next_attempt_at = null,
    last_error = 'Quest identity migration reset',
    finished_at = current_timestamp,
    updated_at = current_timestamp
where action_kind like 'QUEST%'
  and status in ('PREPARED', 'SUBMITTING', 'RECONCILING', 'AMBIGUOUS');

delete from quest_automation_selections;
delete from quest_automation_cycles;
delete from quest_map_execution_counters;
delete from quest_automation_processed_results;

alter table quest_automation_selections add column display_code varchar(100) not null;
alter table quest_automation_selections add column quest_name varchar(255) not null;

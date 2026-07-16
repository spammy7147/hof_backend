update automation_action_runs
set status = 'ABORTED',
    next_attempt_at = null,
    last_error = coalesce(last_error, 'Legacy automation job runtime retired'),
    finished_at = coalesce(finished_at, current_timestamp),
    updated_at = current_timestamp
where status in ('PLANNED', 'RUNNING', 'RETRY_WAIT', 'WAITING_CAPTCHA', 'WAITING_CONFIG')
  and job_id in (
      select id
      from automation_jobs
      where status in ('PENDING', 'RUNNING', 'WAITING_CAPTCHA', 'WAITING_CONFIG', 'WAITING_LOGIN', 'PAUSED')
  );

update automation_jobs
set status = 'CANCELLED',
    message = '레거시 자동화 실행 경로 종료로 취소됨',
    current_module = null,
    current_module_config_id = null,
    current_action = null,
    next_run_at = null,
    last_heartbeat_at = null,
    finished_at = coalesce(finished_at, current_timestamp),
    updated_at = current_timestamp
where status in ('PENDING', 'RUNNING', 'WAITING_CAPTCHA', 'WAITING_CONFIG', 'WAITING_LOGIN', 'PAUSED');

update automation_jobs
set current_module = null,
    current_module_config_id = null,
    current_action = null,
    next_run_at = null,
    last_heartbeat_at = null
where current_module is not null
   or current_module_config_id is not null
   or current_action is not null
   or next_run_at is not null
   or last_heartbeat_at is not null;

update automation_work_sessions
set next_check_at = updated_at
where status = 'YIELDED_PRIORITY'
  and next_check_at is null;

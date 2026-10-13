ALTER TABLE character_sync_jobs ADD COLUMN sync_mode VARCHAR(20) NOT NULL DEFAULT 'DETAIL';
ALTER TABLE character_sync_jobs ADD COLUMN deep_sync_targets TEXT;
ALTER TABLE character_sync_jobs ADD COLUMN current_operation_job_id BIGINT REFERENCES character_operation_jobs(id);

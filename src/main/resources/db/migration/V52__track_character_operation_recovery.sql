ALTER TABLE character_operation_jobs ADD COLUMN recovery_status VARCHAR(20);

ALTER TABLE character_operation_jobs ADD CONSTRAINT chk_character_operation_recovery_status
    CHECK (recovery_status IS NULL OR recovery_status IN
        ('NOT_STARTED', 'REQUIRED', 'RESTORING', 'RESTORED', 'UNAVAILABLE', 'ACCEPTED'));

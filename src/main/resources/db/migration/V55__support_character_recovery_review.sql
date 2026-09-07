ALTER TABLE character_operation_jobs ADD COLUMN restore_attempt_limit INTEGER NOT NULL DEFAULT 3;
ALTER TABLE character_operation_jobs ADD COLUMN recovery_review_token VARCHAR(36);
ALTER TABLE character_operation_jobs ADD COLUMN recovery_review_fingerprint VARCHAR(64);
ALTER TABLE character_operation_jobs ADD COLUMN recovery_reviewed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE character_operation_jobs ADD COLUMN recovery_accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE character_operation_jobs ADD CONSTRAINT ck_character_recovery_attempt_limit CHECK (restore_attempt_limit >= 3);

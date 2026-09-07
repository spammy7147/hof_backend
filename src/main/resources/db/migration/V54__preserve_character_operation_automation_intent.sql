ALTER TABLE typed_automation_runtime_states ADD COLUMN intent_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE character_operation_jobs ADD COLUMN automation_intent_revision BIGINT;
ALTER TABLE character_operation_jobs ADD COLUMN resume_automation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE character_operation_jobs ADD COLUMN automation_released BOOLEAN NOT NULL DEFAULT FALSE;

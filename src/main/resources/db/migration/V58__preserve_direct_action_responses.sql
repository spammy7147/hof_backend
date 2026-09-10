ALTER TABLE typed_automation_action_runs ADD COLUMN direct_response_json TEXT;
ALTER TABLE typed_automation_action_runs ADD COLUMN direct_response_fingerprint VARCHAR(64);
ALTER TABLE typed_automation_action_runs ADD COLUMN direct_response_suppression_released_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE typed_automation_action_runs ADD CONSTRAINT ck_typed_action_direct_response
    CHECK ((direct_response_json IS NULL AND direct_response_fingerprint IS NULL)
        OR (direct_response_json IS NOT NULL AND direct_response_fingerprint IS NOT NULL AND submitted_at IS NOT NULL));
ALTER TABLE typed_automation_action_runs DROP CONSTRAINT ck_typed_action_status;
ALTER TABLE typed_automation_action_runs ADD CONSTRAINT ck_typed_action_status
    CHECK (status IN ('PREPARED', 'SUBMITTING', 'RECONCILING', 'RESULT_PENDING', 'RESULT_HELD', 'SUCCEEDED', 'FAILED', 'AMBIGUOUS'));
ALTER TABLE typed_automation_action_runs ADD CONSTRAINT ck_typed_action_result_pending
    CHECK (status <> 'RESULT_PENDING' OR (direct_response_json IS NOT NULL AND next_attempt_at IS NOT NULL));
ALTER TABLE typed_automation_action_runs ADD CONSTRAINT ck_typed_action_result_held
    CHECK (status <> 'RESULT_HELD' OR (direct_response_json IS NOT NULL AND next_attempt_at IS NULL AND finished_at IS NOT NULL));
ALTER TABLE typed_automation_runtime_states ADD COLUMN direct_response_yield_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE typed_automation_action_runs ADD CONSTRAINT ck_typed_action_direct_response_released
    CHECK (direct_response_suppression_released_at IS NULL OR status = 'RESULT_HELD');
ALTER TABLE typed_automation_action_runs ADD COLUMN direct_response_evidence_case_id VARCHAR(64);
ALTER TABLE automation_evidence_cases ALTER COLUMN attempt_id DROP NOT NULL;
ALTER TABLE automation_evidence_cases ADD COLUMN typed_action_id BIGINT;
ALTER TABLE automation_evidence_cases ADD CONSTRAINT fk_evidence_case_typed_action
    FOREIGN KEY (typed_action_id) REFERENCES typed_automation_action_runs(id) ON DELETE CASCADE;
ALTER TABLE automation_evidence_cases ADD CONSTRAINT ck_evidence_case_owner
    CHECK ((attempt_id IS NOT NULL AND typed_action_id IS NULL)
        OR (attempt_id IS NULL AND typed_action_id IS NOT NULL));
CREATE UNIQUE INDEX uk_evidence_case_typed_action ON automation_evidence_cases(typed_action_id);

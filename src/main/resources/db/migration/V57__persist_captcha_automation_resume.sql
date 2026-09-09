ALTER TABLE captcha_challenges ADD COLUMN automation_resume_pending BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_captcha_pending_automation_resume
    ON captcha_challenges (status, automation_resume_pending, account_id);

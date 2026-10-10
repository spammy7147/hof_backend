ALTER TABLE battle_logs ADD COLUMN detail_archive_status VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE battle_logs ADD COLUMN detail_html TEXT;
ALTER TABLE battle_logs ADD COLUMN detail_archive_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE battle_logs ADD COLUMN detail_archive_next_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE battle_logs ADD COLUMN detail_archive_lease_token VARCHAR(36);
ALTER TABLE battle_logs ADD COLUMN detail_archive_lease_until TIMESTAMP(6) WITH TIME ZONE;

-- 기존 보관본은 전투 응답 요약이며 상세 페이지 수집 완료를 뜻하지 않는다.
UPDATE battle_logs SET detail_archive_status = 'SUMMARY' WHERE archived_html IS NOT NULL;
CREATE INDEX idx_battle_logs_detail_archive_due ON battle_logs(detail_archive_status, detail_archive_next_at);

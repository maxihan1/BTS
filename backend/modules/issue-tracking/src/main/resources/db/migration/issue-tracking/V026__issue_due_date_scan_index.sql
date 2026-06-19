-- due_date 스캔 부분 인덱스 — FR-PL-02. 열린 이슈(deleted_at IS NULL AND resolution_id IS NULL) 기준.

-- 매일 스케줄러가 호출하는 findOpenIssuesDueOn / findOpenOverdueIssues 쿼리 가속용.
-- 부분 인덱스: 열린 이슈만 커버하므로 종료·삭제 이슈 행은 인덱스 대상에서 제외 — 인덱스 크기 절감.
-- 조건절: `deleted_at IS NULL AND resolution_id IS NULL` = "열림(미해결·미삭제)" 이슈.
-- V023/V024 선례와 동일하게 plain CREATE INDEX (CONCURRENTLY 미사용 — Flyway 트랜잭션 내 호환성).
CREATE INDEX idx_issues_due_date_open
    ON issues (due_date)
    WHERE deleted_at IS NULL
      AND resolution_id IS NULL;

COMMENT ON INDEX idx_issues_due_date_open
    IS 'due_date 스캔 부분 인덱스 — 열린 이슈(삭제·종료 제외) 기준. FR-PL-02 스케줄러 쿼리 가속.';

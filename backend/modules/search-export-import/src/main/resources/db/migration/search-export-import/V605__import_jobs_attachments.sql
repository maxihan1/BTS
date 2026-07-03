-- import_jobs.attachments_object_key 추가 (FR-IM-01 PR4) — 첨부 zip MinIO 오브젝트 키
-- ⚠ V번호는 머지 직전 origin/main 의 search-export-import 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).

-- 업로드 시 함께 첨부된 zip(Jira 첨부 원본 묶음)을 저장한 MinIO 오브젝트 키.
-- CSV import 이거나 zip 을 첨부하지 않은 요청은 null(하위호환) — worker 가 null 이면 첨부 복원을 스킵한다.
ALTER TABLE import_jobs ADD COLUMN attachments_object_key VARCHAR(500) NULL;

COMMENT ON COLUMN import_jobs.attachments_object_key IS
    '첨부 zip MinIO 오브젝트 키 — null 이면 첨부 없음(CSV 또는 zip 미첨부)';

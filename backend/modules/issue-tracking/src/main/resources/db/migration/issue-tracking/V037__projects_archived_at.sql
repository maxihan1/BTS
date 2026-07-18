-- projects.archived_at 컬럼 추가 — FR-PJ-04 프로젝트 아카이브 (PR-4). DATA.md §4 TIMESTAMPTZ 강제

-- archived_at: NULL=활성, NOT NULL=아카이브됨. NOT NULL 아님(NULL 허용) — 기존 행은 그대로 활성이라 backfill 불필요.
-- deleted_at(소프트 삭제, DATA.md §3)과 직교하는 별도 라이프사이클 축이며, 읽기 술어는 무변경(PJ4-6).
ALTER TABLE projects ADD COLUMN archived_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN projects.archived_at IS 'NULL=활성, NOT NULL=아카이브됨. 아카이브 시 프로젝트 스코프 쓰기 잠금 (FR-PJ-04). deleted_at 과 직교.';

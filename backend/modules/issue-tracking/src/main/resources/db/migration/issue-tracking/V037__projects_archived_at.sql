-- projects.archived_at 컬럼 추가 — FR-PJ-04 프로젝트 아카이브 (PR-4)

ALTER TABLE projects ADD COLUMN archived_at TIMESTAMPTZ NULL;

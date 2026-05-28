-- V202 — project_workflow_scheme_assignments.project_id BIGINT → UUID 정정
-- spec line 139 의 BIGINT 가정이 projects.id (UUID) 와 불일치 → 정합성 회복.
-- Jira align 정신: 매핑 양쪽 entity ID 타입 통일 (양쪽 모두 UUID).
-- assigned_by (UUID, spec line 142) 와 같은 row 안에서 UUID 통일이 자연스러움.
-- Phase 0 직전 — 시드/실데이터 없음. USING NULL 으로 기존 데이터 비움 (destructive OK).
-- ⚠️ FUTURE MIGRATION CAUTION: V202 의 USING NULL 패턴은 운영 데이터 존재 후 재사용 금지.
-- 실데이터 존재 시 multi-step backfill 필수 — (1) UUID 컬럼 nullable 추가 (2) backfill 스크립트 (3) NOT NULL 강제 + 기존 컬럼 drop.

-- PK + FK 제약 재설정
ALTER TABLE project_workflow_scheme_assignments
    DROP CONSTRAINT IF EXISTS project_workflow_scheme_assignments_pkey,
    DROP CONSTRAINT IF EXISTS project_workflow_scheme_assignments_project_id_fkey;

-- BIGINT → UUID. Phase 0 직전이라 실데이터 없으므로 USING NULL 으로 비움 (DATA.md §마이그레이션).
ALTER TABLE project_workflow_scheme_assignments
    ALTER COLUMN project_id TYPE UUID USING NULL;

-- PK 재설정
ALTER TABLE project_workflow_scheme_assignments
    ADD PRIMARY KEY (project_id);

-- projects.id (UUID) FK — ON DELETE CASCADE (spec NFR-4: 프로젝트 삭제 시 할당도 삭제).
-- issue-tracking V001 (projects 테이블) 은 testRuntimeClasspath cross-BC dep 으로 포함됨.
-- 실행 순서: issue-tracking V001 → project-workflow V200/V201 → V202.
ALTER TABLE project_workflow_scheme_assignments
    ADD CONSTRAINT project_workflow_scheme_assignments_project_id_fkey
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

COMMENT ON COLUMN project_workflow_scheme_assignments.project_id IS
    'projects.id (UUID) FK. ON DELETE CASCADE (spec NFR-4). ADR project-scheme-mapping-jira-align — Jira nodeassociation 정신 (양쪽 entity ID 타입 통일) align. V202 에서 BIGINT → UUID 정정.';

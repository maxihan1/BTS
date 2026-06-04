-- 기본 스킴에 워크플로우 관리 권한 추가 (FR-PM-04, PROJECT_ADMIN 전용)
--
-- 권한 코드 MANAGE_WORKFLOW 는 SDD 12.3(12-permissions.md) 정본 — 워크플로우(스킴) 편집/배정.
-- Jira 식 도메인당 단일 관리 권한. 프로젝트 스킴 배정(ASSIGN_SCHEME/Project)을
-- 프로젝트 관리자(PROJECT_ADMIN)에게만 부여한다 → MEMBER 의 스킴 배정 시 403.
-- (워크플로우 스킴 CRUD(MANAGE_SCHEME/Global)는 시스템 관리자 전용 — role_permissions 가 아닌 SystemPermissionResolver 판정.)
--
-- 결정: spec docs/specs/2026-06-05-fr-pm-04-workflow-automation.md (FR-4, Maxi 2026-06-05 확정).
--
-- 데이터 시드(INSERT)일 뿐 컬럼/스키마 변경이 아니므로 init_codegen.sql 미러 불요.
-- identity-access 는 jOOQ 미사용(JdbcTemplate) — jOOQ 상수 생성 대상 아님.
INSERT INTO role_permissions (scheme_id, role, permission_code)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_WORKFLOW');

-- 기본 스킴에 프로젝트 탐색/이슈 열람 권한 추가 (FR-PM-05, PROJECT_ADMIN·MEMBER 양 역할)
--
-- 권한 코드 BROWSE_PROJECT(프로젝트 탐색) · VIEW_ISSUE(이슈 열람)는 SDD 12.3(12-permissions.md) 정본.
-- 두 권한은 프로젝트 멤버라면 역할과 무관하게 보유하는 기본 열람 권한이므로
-- PROJECT_ADMIN 과 MEMBER 양쪽 역할에 각각 부여한다 (총 4행).
--
-- 근거: FR-PM-05 (이슈/프로젝트 열람 권한) · SDD 12.3 (BROWSE_PROJECT/VIEW_ISSUE).
-- 선례: V013__manage_workflow_permission.sql (FR-PM-04 동형 시드 구조 미러 — 하드코딩 scheme_id 리터럴).
--
-- 데이터 시드(INSERT)일 뿐 컬럼/스키마 변경이 아니므로 init_codegen.sql 미러 불요.
-- identity-access 는 jOOQ 미사용(JdbcTemplate) — jOOQ 상수 생성 대상 아님.
-- role_permissions UNIQUE(scheme_id, role, permission_code) 가 중복 시드를 방지한다.
INSERT INTO role_permissions (scheme_id, role, permission_code)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'BROWSE_PROJECT'),
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'VIEW_ISSUE'),
    ('00000000-0000-0000-0000-000000000001', 'MEMBER', 'BROWSE_PROJECT'),
    ('00000000-0000-0000-0000-000000000001', 'MEMBER', 'VIEW_ISSUE');

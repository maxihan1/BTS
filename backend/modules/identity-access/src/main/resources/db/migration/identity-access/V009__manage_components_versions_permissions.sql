-- 기본 스킴에 버전/컴포넌트 관리 권한 추가 (FR-PM-03, PROJECT_ADMIN 전용)
--
-- 권한 코드 MANAGE_COMPONENTS/MANAGE_VERSIONS 는 SDD 12.3(12-permissions.md:42-43) 정본.
-- Jira 식 도메인당 단일 관리 권한 — 컴포넌트/버전 CRUD 3종을 각각 단일 MANAGE_* 로 묶는다.
-- MEMBER 에는 부여하지 않는다(행정 성격, Jira 'Administer Projects' 계열) → MEMBER CRUD 시 403.
-- 결정: docs/decisions/2026-06-03-version-component-permission-prod-resolver.md (D4, Maxi 2026-06-03).
--
-- 데이터 시드(INSERT)일 뿐 컬럼/스키마 변경이 아니므로 init_codegen.sql 미러 불요.
-- identity-access 는 jOOQ 미사용(JdbcTemplate) — jOOQ 상수 생성 대상 아님.
INSERT INTO role_permissions (scheme_id, role, permission_code)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_COMPONENTS'),
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_VERSIONS');

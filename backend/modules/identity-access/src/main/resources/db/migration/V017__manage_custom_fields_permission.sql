-- 기본 스킴에 커스텀 필드 관리 권한 추가 (FR-IS-10, PROJECT_ADMIN 전용)
--
-- 권한 코드 MANAGE_CUSTOM_FIELDS 는 SDD 12.3(12-permissions.md) 정본.
-- Jira 식 도메인당 단일 관리 권한 — 커스텀 필드 정의 CRUD 3종을 단일 MANAGE_CUSTOM_FIELDS 로 묶는다.
-- MEMBER 에는 부여하지 않는다(행정 성격, Jira 'Administer Projects' 계열) → MEMBER CRUD 시 403.
-- prod 판정기: IdentityAccessCustomFieldPermissionResolver (V009 MANAGE_COMPONENTS 패턴 동형).
--
-- 데이터 시드(INSERT)일 뿐 컬럼/스키마 변경이 아니므로 init_codegen.sql 미러 불요.
-- identity-access 는 jOOQ 미사용(JdbcTemplate) — jOOQ 상수 생성 대상 아님.
INSERT INTO role_permissions (scheme_id, role, permission_code)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_CUSTOM_FIELDS');

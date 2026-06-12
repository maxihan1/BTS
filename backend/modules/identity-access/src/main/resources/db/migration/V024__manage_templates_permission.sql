-- 기본 스킴에 이슈 템플릿 관리 권한 추가 (FR-TM-01, PROJECT_ADMIN 전용)
--
-- 권한 코드 MANAGE_TEMPLATES 는 SDD 12.3(12-permissions.md) 정본.
-- Kotlin 상수 정본: com.bts.shared.permission.TemplatePermission.MANAGE_TEMPLATES (이 리터럴과 일치해야 함).
-- Jira 식 도메인당 단일 관리 권한 — 이슈 템플릿 정의 CRUD 3종을 단일 MANAGE_TEMPLATES 로 묶는다.
-- MEMBER 에는 부여하지 않는다(행정 성격, Jira 'Administer Projects' 계열) → MEMBER CRUD 시 403.
-- prod 판정기: IdentityAccessTemplatePermissionResolver (V017 MANAGE_CUSTOM_FIELDS 패턴 동형).
--
-- 데이터 시드(INSERT)일 뿐 컬럼/스키마 변경이 아니므로 init_codegen.sql 미러 불요.
-- identity-access 는 jOOQ 미사용(JdbcTemplate) — jOOQ 상수 생성 대상 아님.
INSERT INTO role_permissions (scheme_id, role, permission_code)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_TEMPLATES');

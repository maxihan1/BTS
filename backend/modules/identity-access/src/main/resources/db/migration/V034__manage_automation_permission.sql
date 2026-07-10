-- 기본 스킴에 자동화 규칙 관리 권한 추가 (FR-AT-01 D5, PROJECT_ADMIN 전용)
--
-- 권한 코드 MANAGE_AUTOMATION 은 SDD 12.3(12-permissions.md) 정본 — 자동화 규칙(트리거/조건/액션)
-- 관리(CRUD). Jira 식 도메인당 단일 관리 권한이며 프로젝트 행정 권한(grants=[ProjectAdmin])이므로
-- 기본 스킴의 PROJECT_ADMIN 역할에만 부여한다 → MEMBER 의 자동화 룰 관리 시 403.
--
-- 근거: FR-AT-01 (자동화 트리거) · ADR docs/decisions/2026-07-10-fr-at-01-automation-triggers.md D5
--       (MANAGE_AUTOMATION 권한 동반 결선 — dead 시드 회피).
-- 선례: V013__manage_workflow_permission.sql (FR-PM-04 동형 시드 구조 미러).
--
-- 데이터 시드(INSERT)일 뿐 컬럼/스키마 변경이 아니므로 init_codegen.sql 미러 불요.
-- identity-access 는 jOOQ 미사용(JdbcTemplate) — jOOQ 상수 생성 대상 아님.
--
-- 주의(Flyway V번호): 작성 시점 최신은 V033, V034 가 free 였다. 동시 브랜치에서 V034 를 선점할 수
-- 있으므로 머지 직전 identity-access 마이그레이션 최신 번호를 재확인할 것.
INSERT INTO role_permissions (scheme_id, role, permission_code)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_AUTOMATION');

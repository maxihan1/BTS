-- 필드 수준 권한 규칙 테이블 + MANAGE_FIELD_PERMISSIONS 권한코드 시드 (FR-PM-07 PR-A)
--
-- opt-in 제한 모델: 행이 하나도 없으면 모든 필드가 모든 멤버에게 열린다(기본 허용).
-- 특정 (project_id, field_kind, field_key)에 행이 추가되는 순간 그 필드는 제한 대상이 되어,
-- 명시된 group_id 멤버에게만 해당 access_level 이 허용된다(화이트리스트 방식).
-- access_level 은 EDIT ⊃ VIEW 포함관계: EDIT 보유 시 VIEW 도 자동 충족(EDIT 권한이 상위).
--
-- 데이터/스키마 변경(테이블+시드)일 뿐 jOOQ 코드젠 대상이 아니다.
-- identity-access 는 jOOQ 미사용(JdbcTemplate) — init_codegen.sql 미러 불요(V016/V017 선례).

CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid() 안전망 (V001 미적용 환경 대비)

CREATE TABLE field_permissions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   UUID NOT NULL,
    field_kind   VARCHAR(8)  NOT NULL CHECK (field_kind IN ('CORE','CUSTOM')),
    field_key    VARCHAR(64) NOT NULL,
    group_id     UUID NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    access_level VARCHAR(8)  NOT NULL CHECK (access_level IN ('VIEW','EDIT')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, field_kind, field_key, group_id, access_level)
);

-- project_id FK 인덱스 — 프로젝트별 필드 권한 규칙 목록 조회 최적화(PostgreSQL은 FK 인덱스 자동 생성 안 함).
CREATE INDEX idx_field_permissions_project ON field_permissions (project_id);

-- 기본 스킴 PROJECT_ADMIN에 MANAGE_FIELD_PERMISSIONS 부여 (FR-PM-07).
-- 필드 수준 권한 규칙을 생성/수정/삭제하는 행정 권한코드. MEMBER 제외(보수적 시드).
INSERT INTO role_permissions (id, scheme_id, role, permission_code)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_FIELD_PERMISSIONS');

COMMENT ON TABLE  field_permissions              IS '필드 수준 권한 규칙 — opt-in 제한(행 없으면 전체 허용), FR-PM-07';
COMMENT ON COLUMN field_permissions.field_kind   IS '필드 종류: CORE(내장 필드) | CUSTOM(커스텀 필드)';
COMMENT ON COLUMN field_permissions.field_key    IS 'CORE=필드명, CUSTOM=커스텀 필드 키 — field_kind 와 함께 대상 필드 식별';
COMMENT ON COLUMN field_permissions.group_id     IS '허용 대상 사용자 그룹 — 그룹 삭제 시 규칙 연쇄 삭제(ON DELETE CASCADE)';
COMMENT ON COLUMN field_permissions.access_level IS '허용 수준: VIEW(읽기) | EDIT(쓰기). EDIT ⊃ VIEW 포함관계(EDIT 보유 시 VIEW 충족)';

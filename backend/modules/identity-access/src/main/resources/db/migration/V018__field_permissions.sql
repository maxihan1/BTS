-- 필드 수준 권한 규칙 테이블 + MANAGE_FIELD_PERMISSIONS 권한코드 시드 (FR-PM-07 PR-A)

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

CREATE INDEX idx_field_permissions_project ON field_permissions (project_id);

-- 기본 스킴 PROJECT_ADMIN에 MANAGE_FIELD_PERMISSIONS 부여
INSERT INTO role_permissions (id, scheme_id, role, permission_code)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'MANAGE_FIELD_PERMISSIONS');

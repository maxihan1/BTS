-- 전역 시스템 역할(SYSTEM_ADMIN) 할당을 저장하는 테이블 (FR-PM-08)

CREATE TABLE system_role_assignments (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role       VARCHAR(32) NOT NULL CHECK (role IN ('SYSTEM_ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, role)
);

COMMENT ON TABLE  system_role_assignments            IS '전역 시스템 역할 할당 — 사용자에게 부여된 시스템 수준 역할을 저장한다. 프로젝트와 무관한 별개 축 (ADR 2026-06-04 D1).';
COMMENT ON COLUMN system_role_assignments.user_id    IS 'identity-access BC의 users.id. 사용자 삭제 시 할당도 CASCADE 삭제.';
COMMENT ON COLUMN system_role_assignments.role       IS '전역 시스템 역할. 현재 SYSTEM_ADMIN 단일 값.';

CREATE INDEX idx_system_role_assignments_user ON system_role_assignments(user_id);

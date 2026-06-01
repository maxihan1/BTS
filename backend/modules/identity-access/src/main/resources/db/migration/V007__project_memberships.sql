-- 프로젝트 멤버십(사용자×프로젝트 역할)을 저장하는 테이블

CREATE TABLE project_memberships (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID        NOT NULL,                          -- cross-BC(issue-tracking projects) 참조, FK 없음 (ADR D2)
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        VARCHAR(32) NOT NULL CHECK (role IN ('PROJECT_ADMIN','MEMBER')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, user_id)
);

CREATE INDEX idx_project_memberships_project ON project_memberships(project_id);
CREATE INDEX idx_project_memberships_user    ON project_memberships(user_id);
CREATE INDEX idx_project_memberships_admins  ON project_memberships(project_id) WHERE role = 'PROJECT_ADMIN';

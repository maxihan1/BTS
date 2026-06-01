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

COMMENT ON TABLE  project_memberships              IS '프로젝트 멤버십 — 사용자와 프로젝트의 역할 관계를 저장한다. hard delete 정책 (ADR D6).';
COMMENT ON COLUMN project_memberships.project_id   IS 'issue-tracking BC의 projects.id를 참조. cross-BC이므로 DB 수준 FK 없음 (ADR D2).';
COMMENT ON COLUMN project_memberships.user_id      IS 'identity-access BC의 users.id. 사용자 삭제 시 멤버십도 CASCADE 삭제.';
COMMENT ON COLUMN project_memberships.role         IS '프로젝트 내 역할. PROJECT_ADMIN(관리자) 또는 MEMBER(일반 멤버).';

CREATE INDEX idx_project_memberships_project ON project_memberships(project_id);
CREATE INDEX idx_project_memberships_user    ON project_memberships(user_id);
CREATE INDEX idx_project_memberships_admins  ON project_memberships(project_id) WHERE role = 'PROJECT_ADMIN';

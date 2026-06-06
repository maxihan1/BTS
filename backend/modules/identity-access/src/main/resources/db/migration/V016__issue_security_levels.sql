-- 이슈 보안 수준(Issue Security Level) 관리 인프라 — 스킴/등급/멤버/프로젝트적용 + SET_ISSUE_SECURITY 시드 (FR-PM-06 PR-A)
-- Jira Cloud Issue Security 구조: 스킴 → 등급 → 멤버(다형 5종). 스킴을 프로젝트에 0~1개 적용.
-- identity-access raw SQL(jOOQ/init_codegen 미러 불요). 관리 메타라 하드 CRUD 허용(이슈/사용자 소프트삭제와 무관).

-- ── issue_security_schemes ────────────────────────────────────────────────────
-- 보안 스킴 마스터. 등급들의 묶음으로, 여러 프로젝트가 한 스킴을 공유할 수 있다(전역).
CREATE TABLE issue_security_schemes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── issue_security_levels ─────────────────────────────────────────────────────
-- 스킴에 속한 보안 등급("임원만", "내부용" 등). 스킴 삭제 시 연쇄 삭제.
CREATE TABLE issue_security_levels (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id   UUID         NOT NULL REFERENCES issue_security_schemes(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (scheme_id, name)
);

-- scheme_id FK 인덱스 — 스킴별 등급 목록 조회 최적화(PostgreSQL은 FK 인덱스 자동 생성 안 함).
CREATE INDEX ix_issue_security_levels_scheme ON issue_security_levels(scheme_id);

-- 스킴당 기본 등급은 최대 1개 (부분 유니크 인덱스).
CREATE UNIQUE INDEX uq_security_level_one_default
    ON issue_security_levels(scheme_id)
    WHERE is_default;

-- ── issue_security_level_members ──────────────────────────────────────────────
-- 등급을 통과할 수 있는 멤버. member_type별 member_value 다형:
--   USER/GROUP=UUID, PROJECT_ROLE=역할 문자열, REPORTER/ASSIGNEE=NULL.
-- 도메인 SecurityLevelMember.create 검증과 DB CHECK 제약의 2중 방어. 등급 삭제 시 연쇄 삭제.
CREATE TABLE issue_security_level_members (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    level_id     UUID        NOT NULL REFERENCES issue_security_levels(id) ON DELETE CASCADE,
    member_type  VARCHAR(20) NOT NULL
        CHECK (member_type IN ('REPORTER', 'ASSIGNEE', 'USER', 'PROJECT_ROLE', 'GROUP')),
    member_value VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- NULLS NOT DISTINCT(PG15+): REPORTER/ASSIGNEE 는 member_value=NULL 이라 기본 NULLS DISTINCT
    -- 였다면 NULL≠NULL 로 취급돼 같은 등급에 중복 행이 생긴다. NULL 을 동일 값으로 묶어
    -- addMember 의 ON CONFLICT DO NOTHING 이 발화하도록 한다(중복 멤버 멱등, C1).
    UNIQUE NULLS NOT DISTINCT (level_id, member_type, member_value)
);

-- level_id FK 인덱스 — 등급별 멤버 목록 조회 최적화.
CREATE INDEX ix_issue_security_level_members_level ON issue_security_level_members(level_id);

-- ── project_issue_security_schemes ────────────────────────────────────────────
-- 프로젝트 ↔ 보안 스킴 적용표. 프로젝트당 스킴 0~1개(project_id PK).
-- project_id 는 issue-tracking BC 참조 — cross-BC 이므로 FK 없음(V007/V008 선례).
-- scheme_id 는 ON DELETE RESTRICT — 적용 중인 스킴 삭제 차단(먼저 적용 해제 필요).
CREATE TABLE project_issue_security_schemes (
    project_id UUID        PRIMARY KEY,
    scheme_id  UUID        NOT NULL REFERENCES issue_security_schemes(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- scheme_id 인덱스 — 특정 스킴을 적용 중인 프로젝트 목록 조회 최적화.
CREATE INDEX ix_project_issue_security_schemes_scheme ON project_issue_security_schemes(scheme_id);

-- ── SET_ISSUE_SECURITY 권한코드 시드 ──────────────────────────────────────────
-- 이슈에 보안 등급을 지정/변경하는 신규 권한코드(SDD §12.3). 기본 스킴(00000000-…-001)
-- PROJECT_ADMIN 역할에만 부여(MEMBER 제외, 보수적). PermissionSchemaMigrationTest 카운트 12→13.
INSERT INTO role_permissions (scheme_id, role, permission_code)
VALUES ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'SET_ISSUE_SECURITY');

COMMENT ON TABLE  issue_security_schemes               IS '이슈 보안 스킴 — 보안 등급의 묶음(전역, FR-PM-06)';
COMMENT ON TABLE  issue_security_levels                IS '보안 등급 — 스킴당 여러 개, 스킴당 기본 등급 최대 1';
COMMENT ON TABLE  issue_security_level_members         IS '보안 등급 멤버 — 다형 5종(REPORTER/ASSIGNEE/USER/PROJECT_ROLE/GROUP)';
COMMENT ON COLUMN issue_security_level_members.member_value
    IS 'member_type별 값: USER/GROUP=UUID, PROJECT_ROLE=역할 문자열, REPORTER/ASSIGNEE=NULL';
COMMENT ON TABLE  project_issue_security_schemes       IS '프로젝트별 보안 스킴 적용(프로젝트당 0~1, 적용 중 스킴 삭제 RESTRICT)';

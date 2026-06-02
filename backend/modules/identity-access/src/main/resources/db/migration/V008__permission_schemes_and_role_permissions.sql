-- identity-access 권한 스킴 테이블 + 역할-권한 매트릭스 + 프로젝트 연결표 신설 (FR-PM-02 D3)

-- ── permission_schemes ────────────────────────────────────────────────────────
-- 권한 스킴 마스터. 스킴 하나가 여러 프로젝트에 공유될 수 있다.
-- is_default = TRUE 인 스킴은 uq_permission_schemes_default 부분 유니크 인덱스로 단 하나만 허용.
CREATE TABLE permission_schemes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 기본 스킴은 단 하나만 존재해야 한다 (부분 유니크 인덱스)
CREATE UNIQUE INDEX uq_permission_schemes_default
    ON permission_schemes(is_default)
    WHERE is_default = TRUE;

-- ── role_permissions ──────────────────────────────────────────────────────────
-- 스킴 × 역할 × 권한코드 매트릭스. 스킴 삭제 시 연쇄 삭제.
CREATE TABLE role_permissions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id       UUID        NOT NULL REFERENCES permission_schemes(id) ON DELETE CASCADE,
    role            VARCHAR(32) NOT NULL CHECK (role IN ('PROJECT_ADMIN', 'MEMBER')),
    permission_code VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (scheme_id, role, permission_code)
);

-- scheme_id + role 복합 인덱스 — 프로젝트 권한 판정 시 스킴+역할 조합 조회 최적화
CREATE INDEX idx_role_permissions_scheme_role ON role_permissions(scheme_id, role);

-- ── project_permission_scheme ─────────────────────────────────────────────────
-- 프로젝트 ↔ 스킴 연결표. 여러 프로젝트가 같은 scheme_id 를 공유할 수 있다.
-- project_id 는 issue-tracking BC 참조 — cross-BC 이므로 FK 없음 (V007 project_memberships 선례).
-- 미매핑 프로젝트는 is_default = TRUE 스킴을 fallback 으로 사용한다.
CREATE TABLE project_permission_scheme (
    project_id UUID        PRIMARY KEY,
    scheme_id  UUID        NOT NULL REFERENCES permission_schemes(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- scheme_id 인덱스 — 특정 스킴에 매핑된 프로젝트 목록 조회 최적화
CREATE INDEX idx_pps_scheme ON project_permission_scheme(scheme_id);

-- ── 기본 스킴 시드 (FR-4) ─────────────────────────────────────────────────────
-- 고정 UUID: 00000000-0000-0000-0000-000000000001
-- 이 상수는 JdbcPermissionSchemeRepository 의 fallback 쿼리 및 통합 테스트에서 참조된다.
INSERT INTO permission_schemes (id, name, description, is_default)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Default Permission Scheme',
    '기본 이슈 권한 스킴 (FR-PM-02)',
    TRUE
);

-- 역할-권한 매트릭스 시드 (총 5행)
-- PROJECT_ADMIN: CREATE_ISSUE + EDIT_ISSUE + DELETE_ISSUE (3행)
-- MEMBER:        CREATE_ISSUE + EDIT_ISSUE                (2행, DELETE 제외)
INSERT INTO role_permissions (scheme_id, role, permission_code)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'CREATE_ISSUE'),
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'EDIT_ISSUE'),
    ('00000000-0000-0000-0000-000000000001', 'PROJECT_ADMIN', 'DELETE_ISSUE'),
    ('00000000-0000-0000-0000-000000000001', 'MEMBER',        'CREATE_ISSUE'),
    ('00000000-0000-0000-0000-000000000001', 'MEMBER',        'EDIT_ISSUE');

-- project_permission_scheme 는 시드 없음.
-- 모든 프로젝트는 초기 상태에서 기본 스킴(00000000-0000-0000-0000-000000000001) fallback.
-- 스킴 할당 API/UI 는 후속 FR 에서 행 추가로 구성한다.

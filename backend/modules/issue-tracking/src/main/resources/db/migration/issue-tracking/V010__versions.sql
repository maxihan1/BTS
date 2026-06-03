-- 프로젝트별 버전(릴리스 단위) 테이블 — FR-VR-01. DATA.md §3 소프트 삭제 + §7 FK 인덱스 준수

-- versions 테이블
-- 프로젝트의 릴리스 버전(예: v1.0.0, 2026 Q2). 같은 BC(issue-tracking)이므로 projects 실 FK 적용.
-- start_date / release_date: 버전 시작/릴리스 예정일 (선택, 날짜 단위라 DATE).
-- status 컬럼은 FR-VR-02 로 이연 — 본 마이그레이션 미포함.
-- deleted_at: 소프트 삭제 컬럼 (DATA.md §3) — NULL=활성, NOT NULL=삭제됨.
CREATE TABLE versions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects(id),
    name          VARCHAR(255) NOT NULL,
    description   TEXT         NULL,
    start_date    DATE         NULL,
    release_date  DATE         NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  versions              IS '프로젝트별 버전(릴리스 단위). 같은 BC 라 projects 실 FK 적용 (FR-VR-01).';
COMMENT ON COLUMN versions.project_id   IS '소속 프로젝트 (projects.id). 같은 BC(issue-tracking) 이므로 실 FK 적용.';
COMMENT ON COLUMN versions.name         IS '버전 이름. 활성(deleted_at IS NULL) 기준 프로젝트 내 유일 (부분 유니크 인덱스).';
COMMENT ON COLUMN versions.description  IS '버전 설명 (선택).';
COMMENT ON COLUMN versions.start_date   IS '버전 시작 예정일 (선택). 날짜 단위라 DATE.';
COMMENT ON COLUMN versions.release_date IS '버전 릴리스 예정일 (선택). 날짜 단위라 DATE.';
COMMENT ON COLUMN versions.deleted_at   IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3). 삭제 후 동명 재생성 허용.';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함)
CREATE INDEX idx_versions_project_id ON versions(project_id);

-- 부분 유니크 인덱스: 활성(deleted_at IS NULL) 기준 프로젝트별 버전 이름 유일.
-- 소프트 삭제된 row 는 제외되므로 삭제 후 동명 재생성 허용 (V009 components 부분 인덱스 패턴 참고).
CREATE UNIQUE INDEX ux_versions_project_id_name_active
    ON versions(project_id, name) WHERE deleted_at IS NULL;

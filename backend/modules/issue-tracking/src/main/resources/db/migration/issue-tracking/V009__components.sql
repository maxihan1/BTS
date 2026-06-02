-- 프로젝트별 컴포넌트(하위 영역 분류) 테이블 — FR-CM-01. DATA.md §3 소프트 삭제 + §7 FK 인덱스 준수

-- components 테이블
-- 프로젝트 내부의 하위 영역 분류(예: 백엔드, 프론트엔드). 같은 BC(issue-tracking)이므로 projects 실 FK 적용.
-- lead_user_id: identity-access BC users.id 대응. BC 격리 원칙으로 FK 미적용 — ApplicationService 가 존재 guard.
-- deleted_at: 소프트 삭제 컬럼 (DATA.md §3) — NULL=활성, NOT NULL=삭제됨.
CREATE TABLE components (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects(id),
    name          VARCHAR(255) NOT NULL,
    description   TEXT         NULL,
    lead_user_id  UUID         NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  components              IS '프로젝트별 컴포넌트(하위 영역 분류). 같은 BC 라 projects 실 FK 적용 (FR-CM-01).';
COMMENT ON COLUMN components.project_id   IS '소속 프로젝트 (projects.id). 같은 BC(issue-tracking) 이므로 실 FK 적용.';
COMMENT ON COLUMN components.name         IS '컴포넌트 이름. 활성(deleted_at IS NULL) 기준 프로젝트 내 유일 (부분 유니크 인덱스).';
COMMENT ON COLUMN components.description  IS '컴포넌트 설명 (선택).';
COMMENT ON COLUMN components.lead_user_id IS 'identity-access BC users.id 대응 컴포넌트 리드. BC 격리로 FK 미적용 — ApplicationService 가 존재 guard.';
COMMENT ON COLUMN components.deleted_at   IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3). 삭제 후 동명 재생성 허용.';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함)
CREATE INDEX idx_components_project_id ON components(project_id);

-- 부분 유니크 인덱스: 활성(deleted_at IS NULL) 기준 프로젝트별 컴포넌트 이름 유일.
-- 소프트 삭제된 row 는 제외되므로 삭제 후 동명 재생성 허용 (issues V001 부분 인덱스 패턴 참고).
CREATE UNIQUE INDEX ux_components_project_id_name_active
    ON components(project_id, name) WHERE deleted_at IS NULL;

-- 프로젝트+이슈 타입 조합별 이슈 본문 기본 템플릿 테이블 — FR-TM-01. DATA.md §3 소프트 삭제 + §4 TIMESTAMPTZ + §7 FK 인덱스 준수

-- issue_templates 테이블
-- 프로젝트와 이슈 타입 조합마다 이슈 본문 기본 템플릿을 정의 (예: "버그 리포트" 타입의 재현 절차/기대 결과 섹션).
-- 이슈 생성 시 description 이 비어 있으면 서버가 활성 템플릿 content 를 주입 (적용 방식 옵션 C).
-- 같은 BC(issue-tracking)이므로 projects/issue_types 실 FK 적용 (custom_field_definitions 선례 동형).
-- deleted_at: 소프트 삭제 컬럼 (DATA.md §3) — NULL=활성, NOT NULL=삭제됨. 삭제 후 동일 (project,type) 재생성 허용.
CREATE TABLE issue_templates (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects(id),
    -- issue_type_id: 템플릿이 적용될 이슈 타입 (issue_types.id, BIGINT). 같은 BC 라 실 FK 적용.
    issue_type_id BIGINT       NOT NULL REFERENCES issue_types(id),
    -- name: 템플릿 표시명. 활성 기준 (project,type) 당 1개라 사실상 조합당 단일 명칭.
    name          VARCHAR(100) NOT NULL,
    -- content: 이슈 본문 기본값 (Markdown, 이슈 description 형식). FR-TM-02 변수 치환은 후속.
    content       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  issue_templates               IS '프로젝트+이슈 타입 조합별 이슈 본문 기본 템플릿 (FR-TM-01). 같은 BC 라 projects/issue_types 실 FK 적용.';
COMMENT ON COLUMN issue_templates.project_id    IS '소속 프로젝트 (projects.id). 같은 BC(issue-tracking) 이므로 실 FK 적용.';
COMMENT ON COLUMN issue_templates.issue_type_id IS '적용 이슈 타입 (issue_types.id, BIGINT). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_templates.name          IS '템플릿 표시명 (≤100자).';
COMMENT ON COLUMN issue_templates.content       IS '이슈 본문 기본값 (Markdown, description 형식). 이슈 생성 시 description 공백이면 서버가 주입.';
COMMENT ON COLUMN issue_templates.deleted_at    IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3). 삭제 후 동일 (project,type) 재생성 허용.';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함)
CREATE INDEX idx_issue_templates_project_id    ON issue_templates(project_id);
CREATE INDEX idx_issue_templates_issue_type_id ON issue_templates(issue_type_id);

-- 부분 유니크 인덱스: 활성(deleted_at IS NULL) 기준 (project,type) 조합당 템플릿 1개.
-- 소프트 삭제된 row 는 제외되므로 삭제 후 동일 조합 재생성 허용 (custom_field_definitions 부분 유니크 패턴 동형).
CREATE UNIQUE INDEX ux_issue_templates_project_type_active
    ON issue_templates(project_id, issue_type_id) WHERE deleted_at IS NULL;

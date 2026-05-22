-- issue-tracking 모듈 초기 스키마 — projects + issues + issue_key_redirects. DATA.md §1.1 이슈 키 영속성 + §3 소프트 삭제 준수

-- 1. pgcrypto extension (gen_random_uuid 용)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 2. projects 테이블
-- 이슈 키 prefix(key) 와 다음 발번 시퀀스(key_sequence) 를 보유하는 이슈 컨테이너.
-- key 는 영구 보존 (DATA.md §1.1) — 소프트 삭제 후에도 재사용 불가.
-- deleted_at: 소프트 삭제 컬럼 (DATA.md §3) — NULL 이면 활성, NOT NULL 이면 삭제됨.
CREATE TABLE projects (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- key: 프로젝트 식별 접두사. 대문자 알파벳으로 시작, 대문자+숫자 2~10자.
    -- 예) "BTS", "ATLAS1". CHECK 정규식: ^[A-Z][A-Z0-9]{1,9}$
    key           VARCHAR(10)  NOT NULL UNIQUE CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name          VARCHAR(255) NOT NULL,
    -- key_sequence: 다음 이슈에 부여할 번호. 이슈 생성마다 1 씩 증가.
    key_sequence  BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  projects              IS '이슈 컨테이너. key 는 영구 보존 (DATA.md §1.1).';
COMMENT ON COLUMN projects.key          IS '프로젝트 접두사 — 대문자로 시작, 대문자+숫자 2~10자 (예: BTS, ATLAS1). 이슈 키 생성의 기반.';
COMMENT ON COLUMN projects.key_sequence IS '다음 이슈에 부여할 일련번호. 이슈 생성 시 ApplicationService 가 SELECT FOR UPDATE 후 증가.';
COMMENT ON COLUMN projects.deleted_at   IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3).';

-- 3. issues 테이블
-- 이슈 코어. key 는 영구 UNIQUE — 소프트 삭제 후에도 동일 key 재발급 불가 (DATA.md §1.1).
-- reporter_id: identity-access users(id) 참조. BC 격리 원칙에 따라 FK 미적용,
--   ApplicationService 레이어에서 존재 여부를 guard 한다.
-- current_state_key: project-workflow 의 workflow_states.key 참조. 마찬가지로 BC 격리로 FK 미적용.
-- version: 낙관적 잠금(optimistic locking) 용 카운터. 업데이트마다 1 씩 증가.
CREATE TABLE issues (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- key: 이슈 전역 식별자. 프로젝트 접두사-번호 형식 (예: BTS-1, ATLAS1-42).
    -- CHECK 정규식: ^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$ (번호는 0 으로 시작 불가)
    key                VARCHAR(20)  NOT NULL UNIQUE CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$'),
    project_id         UUID         NOT NULL REFERENCES projects(id),
    summary            VARCHAR(255) NOT NULL,
    -- reporter_id: 이슈 제보자 ID. identity-access BC 의 users.id 와 대응.
    -- BC 격리 원칙으로 FK 미적용 — ApplicationService 가 guard.
    reporter_id        UUID         NOT NULL,
    -- current_state_key: 현재 워크플로우 상태 키. project-workflow BC 의 workflow_states.key 와 대응.
    -- BC 격리 원칙으로 FK 미적용 — ApplicationService 가 guard.
    current_state_key  VARCHAR(50)  NOT NULL,
    -- version: 낙관적 잠금 카운터. UPDATE 시 WHERE version = ? 조건으로 동시 수정 충돌 감지.
    version            BIGINT       NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  issues                     IS '이슈 단건. key 는 영구 보존 — 소프트 삭제 후에도 row 잔존 (DATA.md §1.1).';
COMMENT ON COLUMN issues.key                 IS '이슈 전역 식별자 (예: BTS-1). UNIQUE 제약으로 삭제 후 재발급 불가.';
COMMENT ON COLUMN issues.reporter_id         IS 'identity-access BC users.id 대응. BC 격리로 FK 미적용 — ApplicationService 가 존재 guard.';
COMMENT ON COLUMN issues.current_state_key   IS 'project-workflow BC workflow_states.key 대응. BC 격리로 FK 미적용.';
COMMENT ON COLUMN issues.version             IS '낙관적 잠금 카운터. 동시 수정 충돌 감지용.';
COMMENT ON COLUMN issues.deleted_at          IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3). key 는 삭제 후에도 UNIQUE 제약 유지.';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함)
CREATE INDEX idx_issues_project_id ON issues(project_id);

-- 부분 인덱스: 활성 이슈(deleted_at IS NULL) 기준 프로젝트별 조회 최적화.
-- idx_<table>_<columns> 패턴: project_id + deleted_at 복합 (부분 인덱스로 NULL 행만 커버)
CREATE INDEX idx_issues_project_id_deleted_at ON issues(project_id, deleted_at) WHERE deleted_at IS NULL;

-- 4. issue_key_redirects 테이블
-- 이슈 이동(FR-MV-01) 시 old_key → new_key 영구 매핑 테이블.
-- append-only: UPDATE / DELETE 는 트리거로 차단 (DATA.md §1.1).
-- 본 PR 에서는 스키마만 생성. 실제 사용은 FR-MV-01 활성화 시.
CREATE TABLE issue_key_redirects (
    -- old_key: 이전 이슈 키 (Primary Key — 검색 기준)
    old_key        VARCHAR(20)  PRIMARY KEY,
    -- new_key: 이동 후 현재 이슈 키
    new_key        VARCHAR(20)  NOT NULL,
    redirected_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  issue_key_redirects             IS '이슈 이동 시 옛 키 → 새 키 영구 매핑. FR-MV-01 활성화 시 사용. append-only (DATA.md §1.1).';
COMMENT ON COLUMN issue_key_redirects.old_key     IS '이전 이슈 키 (PK). 한 번 기록된 값 수정/삭제 불가.';
COMMENT ON COLUMN issue_key_redirects.new_key     IS '이동 후 현재 이슈 키. 체인 이동 시 최종 키로 업데이트하지 않음 — 조회 시 체인 순회.';
COMMENT ON COLUMN issue_key_redirects.redirected_at IS '리다이렉트 기록 시각.';

-- new_key 조회 인덱스 (역방향 조회: 현재 키로 이전 키들 조회 시 사용)
CREATE INDEX idx_issue_key_redirects_new_key ON issue_key_redirects(new_key);

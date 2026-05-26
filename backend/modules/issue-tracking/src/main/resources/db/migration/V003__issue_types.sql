-- issue-tracking V003 — issue_types 테이블 + 5 표준 타입 seed (FR-WF-02 cross-BC 사전 도입)
-- spec §5.2.1 issue-tracking BC IssueType 5 표준 seed (FR-WF-02 cross-BC 사전 도입)
-- BC 격리 의식적 예외 — project-workflow PR 안에 issue-tracking 의 V003 마이그레이션 도입.
-- ADR 후보: issue-type-cross-bc-introduction

-- 1. issue_types 테이블
-- 이슈 유형 정의. is_standard=true 인 5개는 시스템 표준 타입 — 삭제/변경 비권장.
-- deleted_at: 소프트 삭제 컬럼 (DATA.md §3) — NULL 이면 활성, NOT NULL 이면 삭제됨.
CREATE TABLE issue_types (
    id           BIGSERIAL    PRIMARY KEY,
    -- key: URL-safe 소문자 식별자. ^[a-z][a-z0-9-]{1,29}$ 형식 (IssueTypeKey VO 와 동일 규칙).
    key          VARCHAR(30)  NOT NULL UNIQUE,
    name         VARCHAR(64)  NOT NULL,
    description  TEXT,
    icon_name    VARCHAR(64),
    -- is_standard: 시스템 기본 제공 타입 여부. true 인 row 는 변경/삭제 비권장.
    is_standard  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMPTZ
);

COMMENT ON TABLE  issue_types             IS 'issue 유형 정의. is_standard=true 는 시스템 표준 5개 타입.';
COMMENT ON COLUMN issue_types.key         IS 'URL-safe 소문자 식별자. IssueTypeKey VO 패턴과 동일.';
COMMENT ON COLUMN issue_types.is_standard IS 'true = 시스템 표준 타입 — 삭제/변경 비권장.';
COMMENT ON COLUMN issue_types.deleted_at  IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3).';

-- 2. 부분 인덱스 — 활성 타입(deleted_at IS NULL) 기준 key 조회 최적화
-- ix_<table>_<column>_<modifier> 패턴 (V001 인덱스 네이밍 일관)
CREATE INDEX ix_issue_types_key_active ON issue_types (key) WHERE deleted_at IS NULL;

-- 3. 5 표준 타입 seed
-- spec §5.2.1 표준 타입: epic, story, task, subtask, bug
-- is_standard = true — 시스템 표준 타입 식별자
INSERT INTO issue_types (key, name, description, icon_name, is_standard) VALUES
    ('epic',    'Epic',    '큰 단위 기획',    'epic',    true),
    ('story',   'Story',   '사용자 시나리오', 'story',   true),
    ('task',    'Task',    '개별 작업',       'task',    true),
    ('subtask', 'Subtask', '하위 작업',       'subtask', true),
    ('bug',     'Bug',     '결함',            'bug',     true);

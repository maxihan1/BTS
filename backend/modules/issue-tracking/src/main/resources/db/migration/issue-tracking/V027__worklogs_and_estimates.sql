-- worklog 시간 기록 테이블 + 이슈 추정 시간 컬럼 추가 — FR-TT-01 (작업 시간 기록/추정)

-- worklogs: 이슈에 기록한 작업 시간 단건. 엔티티 테이블이라 deleted_at 소프트 삭제 적용 (DATA.md §3).
-- issue_id 는 같은 BC(issue-tracking) 라 실 FK + ON DELETE CASCADE (이슈 하드 삭제 경로에서 고아 worklog 자동 정리).
-- author_id 는 identity-access BC users.id 대응이나 BC 격리로 FK 미적용 (assignee_id 선례).
-- time_spent_seconds 는 0/음수 무의미라 CHECK ( > 0 ) 로 도메인 불변식 DB 차원 강제.
CREATE TABLE worklogs (
    id                 UUID         PRIMARY KEY,
    issue_id           UUID         NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    author_id          UUID         NOT NULL,
    time_spent_seconds INT          NOT NULL CHECK (time_spent_seconds > 0),
    started_at         TIMESTAMPTZ  NOT NULL,
    comment            TEXT         NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  worklogs                    IS '이슈 작업 시간 기록 단건. 엔티티라 소프트 삭제 적용 (FR-TT-01, DATA.md §3).';
COMMENT ON COLUMN worklogs.issue_id           IS '대상 이슈 (issues.id). 같은 BC 라 실 FK + ON DELETE CASCADE.';
COMMENT ON COLUMN worklogs.author_id          IS '작성자 사용자 ID (identity-access users.id 대응). BC 격리로 FK 미적용.';
COMMENT ON COLUMN worklogs.time_spent_seconds IS '기록한 작업 시간(초). 양수만 허용 (CHECK > 0).';
COMMENT ON COLUMN worklogs.started_at         IS '작업 시작 시각. 시각 성분 있어 TIMESTAMPTZ (DATA.md §4).';
COMMENT ON COLUMN worklogs.comment            IS '작업 내용 메모 (선택).';
COMMENT ON COLUMN worklogs.deleted_at         IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3).';

-- FK 인덱스 (DATA.md §7). 이슈별 활성 worklog 목록 조회용 — 삭제분 제외 부분 인덱스.
CREATE INDEX idx_worklogs_issue_id ON worklogs (issue_id) WHERE deleted_at IS NULL;

-- 작성자별 기간 조회용(개인 작업 시간 집계). started_at 정렬 동반이라 복합 인덱스.
CREATE INDEX idx_worklogs_author_started ON worklogs (author_id, started_at);

-- issues 추정 시간 3컬럼. original/remaining 은 선택(NULL=미추정), time_spent 은 집계 캐시라 NOT NULL DEFAULT 0 (DATA.md §4 #2).
ALTER TABLE issues
    ADD COLUMN original_estimate_seconds  INT NULL,
    ADD COLUMN time_spent_seconds         INT NOT NULL DEFAULT 0,
    ADD COLUMN remaining_estimate_seconds INT NULL;

COMMENT ON COLUMN issues.original_estimate_seconds  IS '최초 추정 작업 시간(초). 선택(NULL=미추정).';
COMMENT ON COLUMN issues.time_spent_seconds         IS '누적 기록 작업 시간(초). worklogs 합계 캐시 — NOT NULL DEFAULT 0.';
COMMENT ON COLUMN issues.remaining_estimate_seconds IS '잔여 추정 작업 시간(초). 선택(NULL=미추정).';

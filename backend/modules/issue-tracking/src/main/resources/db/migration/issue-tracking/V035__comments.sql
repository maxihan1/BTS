-- 댓글 테이블 신설 — 이슈에 작성된 댓글 기록 (FR-IM-01 PR3 댓글 도메인 신설 + 댓글/Worklog Import)

-- comments: 이슈에 작성된 댓글 단건. 엔티티 테이블이라 deleted_at 소프트 삭제 적용 (DATA.md §3).
-- issue_id 는 같은 BC(issue-tracking) 라 실 FK + ON DELETE CASCADE (이슈 하드 삭제 경로에서 고아 댓글 자동 정리, worklog V027 선례).
-- author_id 는 identity-access BC users.id 대응이나 BC 격리로 FK 미적용 (assignee_id / worklog author_id 선례).
CREATE TABLE comments (
    id         UUID        PRIMARY KEY,
    issue_id   UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    author_id  UUID        NOT NULL,
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ NULL
);
COMMENT ON TABLE  comments            IS '이슈에 작성된 댓글 단건. 엔티티라 소프트 삭제 적용 (FR-IM-01 PR3, DATA.md §3).';
COMMENT ON COLUMN comments.issue_id   IS '대상 이슈 (issues.id). 같은 BC 라 실 FK + ON DELETE CASCADE.';
COMMENT ON COLUMN comments.author_id  IS '작성자 사용자 ID (identity-access users.id 대응). BC 격리로 FK 미적용.';
COMMENT ON COLUMN comments.body       IS '댓글 본문 (raw markdown 원문).';
COMMENT ON COLUMN comments.deleted_at IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3).';

-- FK 인덱스 (DATA.md §7). 이슈별 활성 댓글 목록 조회용(created_at 정렬 동반) — 삭제분 제외 부분 인덱스.
CREATE INDEX idx_comments_issue_created ON comments (issue_id, created_at) WHERE deleted_at IS NULL;

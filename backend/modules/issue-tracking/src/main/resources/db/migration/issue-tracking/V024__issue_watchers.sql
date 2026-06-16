-- 이슈 워처(관심 등록 사용자) 조인 테이블 — FR-WT-01.

CREATE TABLE issue_watchers (
    issue_id   UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);
CREATE INDEX idx_issue_watchers_issue ON issue_watchers (issue_id);

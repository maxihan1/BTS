-- 이슈 링크(issue_links 4종) + parent-child 계층(issues.parent_id) 스키마 — FR-LK-01. 링크는 관계 테이블이라 소프트 삭제 없음(해제 = 행 DELETE). V017 issue_version_links 동형.

-- ── issue_links: 이슈↔이슈 방향성 링크(blocks/relates/duplicates/clones) ──────────
CREATE TABLE issue_links (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id  UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    target_id  UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    link_type  VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_issue_links_no_self CHECK (source_id <> target_id),
    CONSTRAINT chk_issue_links_type    CHECK (link_type IN ('blocks', 'relates', 'duplicates', 'clones')),
    CONSTRAINT uq_issue_links          UNIQUE (source_id, target_id, link_type)
);

CREATE INDEX idx_issue_links_source_id ON issue_links(source_id);
CREATE INDEX idx_issue_links_target_id ON issue_links(target_id);

-- ── issues.parent_id: 구조적 parent-child 계층 (링크와 별개 메커니즘) ──────────────
ALTER TABLE issues ADD COLUMN parent_id UUID NULL REFERENCES issues(id);

CREATE INDEX idx_issues_parent_id ON issues(parent_id);

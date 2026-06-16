-- 이슈 워처(관심 등록 사용자) 조인 테이블 — FR-WT-01.

-- ── issue_watchers: 이슈에 관심 등록한 사용자 ─────────────────────────────────
-- 이슈↔사용자 다대다 조인 테이블이라 (issue_id, user_id) 복합 PK 로 멱등성을 보장한다 (중복 등록 차단).
-- 소프트 삭제 미적용: 조인 테이블은 관심 해제 = 행 즉시 제거(하드 remove)가 정상 (DATA.md 소프트 삭제 예외).
--   deleted_at 컬럼 없음.
-- issue_id FK + ON DELETE CASCADE: 같은 BC(issue-tracking) 내부라 issues 실 FK. 이슈가 하드 삭제되는
--   경로(테스트 cleanup 등)에서 고아 워처 행을 자동 정리해 FK 위반을 막는다.
-- user_id: identity-access BC users.id 대응. BC 격리 원칙으로 FK 미적용 — assignee_id 선례 (ApplicationService 가 guard).
CREATE TABLE issue_watchers (
    issue_id   UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);
COMMENT ON TABLE  issue_watchers            IS '이슈 워처(관심 등록 사용자) 조인 테이블. 다대다라 복합 PK, 소프트 삭제 없음 (FR-WT-01).';
COMMENT ON COLUMN issue_watchers.issue_id   IS '대상 이슈 (issues.id). 같은 BC 라 실 FK + ON DELETE CASCADE.';
COMMENT ON COLUMN issue_watchers.user_id    IS '관심 등록 사용자 ID (identity-access users.id 대응). BC 격리로 FK 미적용.';
COMMENT ON COLUMN issue_watchers.created_at IS '관심 등록 시각. TIMESTAMPTZ (DATA.md §4).';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함).
-- issue_id 단독 조회(이슈별 워처 목록)에 쓰이므로 명시 인덱스로 의도를 고정한다 (PK 선두 컬럼이긴 하나 명시).
CREATE INDEX idx_issue_watchers_issue ON issue_watchers (issue_id);

-- 이슈↔버전 다대다 연결 테이블 2종 — FR-VR-03. 관계 테이블이라 소프트 삭제 없음(연결 해제 = 행 DELETE). V012 issue_components 동형.

-- ── issue_affects_versions: 이슈가 "영향받는" 버전 연결(조인) 테이블 ──────────────
-- 한 이슈가 여러 버전에 영향받고, 한 버전이 여러 이슈에서 참조되는 N:M 관계를 표현.
-- 같은 BC(issue-tracking) 내부 테이블이므로 issues / versions 양쪽 실 FK 적용.
-- 소프트 삭제 미적용: 연결 자체는 도메인 엔티티가 아닌 순수 관계이므로 해제 = 행 DELETE (DATA.md §3 대상 아님).
-- ON DELETE CASCADE: 연결 행은 양쪽 엔티티가 없으면 존재 의미가 없는 순수 관계다. prod 는 issues/versions 를
-- 소프트 삭제(deleted_at)하므로 cascade 가 발화하지 않는다(동작 변화 0). 엔티티 하드 삭제 경로(테스트 cleanup 등)에서만
-- 고아 연결 행을 자동 정리해 FK 위반을 막는다(조인 테이블 표준).
CREATE TABLE issue_affects_versions (
    issue_id   UUID        NOT NULL REFERENCES issues(id)   ON DELETE CASCADE,
    version_id UUID        NOT NULL REFERENCES versions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (issue_id, version_id)
);
COMMENT ON TABLE  issue_affects_versions            IS '이슈↔영향받는 버전 N:M 연결. 관계 테이블이라 소프트 삭제 없음 (FR-VR-03).';
COMMENT ON COLUMN issue_affects_versions.issue_id   IS '연결된 이슈 (issues.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_affects_versions.version_id IS '영향받는 버전 (versions.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_affects_versions.created_at IS '연결 생성 시각. TIMESTAMPTZ (DATA.md §4).';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함).
-- FK 는 issue_id / version_id 두 개지만 인덱스는 version_id 만 추가한다.
-- issue_id 는 복합 PK (issue_id, version_id) 의 선두 컬럼이라 PK 인덱스가 이미 커버하므로 중복 불요.
-- version_id 는 PK 후미 컬럼이라 단독 조회/조인(버전→이슈 역방향)에 활용 못 해 별도 인덱스가 필요.
CREATE INDEX idx_issue_affects_versions_version_id ON issue_affects_versions(version_id);

-- ── issue_fix_versions: 이슈가 "수정될 예정인" 버전 연결(조인) 테이블 ───────────────
-- issue_affects_versions 와 구조 동일. 의미만 다르다(영향받는 버전 vs 수정 예정 버전).
CREATE TABLE issue_fix_versions (
    issue_id   UUID        NOT NULL REFERENCES issues(id)   ON DELETE CASCADE,
    version_id UUID        NOT NULL REFERENCES versions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (issue_id, version_id)
);
COMMENT ON TABLE  issue_fix_versions            IS '이슈↔수정 예정 버전 N:M 연결. 관계 테이블이라 소프트 삭제 없음 (FR-VR-03).';
COMMENT ON COLUMN issue_fix_versions.issue_id   IS '연결된 이슈 (issues.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_fix_versions.version_id IS '수정 예정 버전 (versions.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_fix_versions.created_at IS '연결 생성 시각. TIMESTAMPTZ (DATA.md §4).';

-- FK 인덱스 (DATA.md §7). version_id 만 추가: 복합 PK 선두 issue_id 는 PK 인덱스가 커버,
-- version_id 는 PK 후미라 역방향(버전→이슈) 조인에 단독 인덱스가 필요.
CREATE INDEX idx_issue_fix_versions_version_id ON issue_fix_versions(version_id);

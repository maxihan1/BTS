-- 이슈 링크(issue_links 4종) + parent-child 계층(issues.parent_id) 스키마 — FR-LK-01. 링크는 관계 테이블이라 소프트 삭제 없음(해제 = 행 DELETE). V017 issue_version_links 동형.

-- ── issue_links: 이슈↔이슈 방향성 링크(blocks/relates/duplicates/clones) ──────────
-- 한 이슈가 여러 이슈와 4종 link_type 으로 연결되는 방향성 관계를 표현한다 (source → target).
-- 같은 BC(issue-tracking) 내부 테이블이므로 source / target 양쪽 모두 issues 실 FK 적용.
-- 소프트 삭제 미적용: 링크는 도메인 엔티티가 아닌 순수 관계이므로 해제 = 행 DELETE (DATA.md §3 대상 아님).
-- ON DELETE CASCADE: 링크 행은 양쪽 이슈가 없으면 존재 의미가 없는 순수 관계다. prod 는 issues 를
--   소프트 삭제(deleted_at)하므로 cascade 가 발화하지 않는다(동작 변화 0). 이슈 하드 삭제 경로(테스트 cleanup 등)에서만
--   고아 링크 행을 자동 정리해 FK 위반을 막는다(조인 테이블 표준, V017 동일).
-- created_by 컬럼 없음: SDD §5.7 · 형제 V017 정합 (관계 테이블은 생성 주체를 보존하지 않음).
-- SDD §5.7 은 source/target 을 BIGINT 로 표기했으나 실제 issues.id 가 UUID(V001)라 UUID FK 로 구현(ADR deviation).
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
COMMENT ON TABLE  issue_links            IS '이슈↔이슈 방향성 링크 (blocks/relates/duplicates/clones). 관계 테이블이라 소프트 삭제 없음 (FR-LK-01).';
COMMENT ON COLUMN issue_links.id         IS '링크 식별자 (IDENTITY). parent-child 와 달리 링크는 다대다라 별도 PK 필요.';
COMMENT ON COLUMN issue_links.source_id  IS '링크 출발 이슈 (issues.id). 같은 BC 라 실 FK + ON DELETE CASCADE.';
COMMENT ON COLUMN issue_links.target_id  IS '링크 도착 이슈 (issues.id). 같은 BC 라 실 FK + ON DELETE CASCADE.';
COMMENT ON COLUMN issue_links.link_type  IS '링크 종류 — blocks/relates/duplicates/clones 4종 (CHECK 제약으로 고정).';
COMMENT ON COLUMN issue_links.created_at IS '링크 생성 시각. TIMESTAMPTZ (DATA.md §4).';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함).
-- source_id / target_id 양쪽 모두 단독 조회(이슈의 나가는/들어오는 링크 목록)에 쓰이므로 둘 다 인덱스.
CREATE INDEX idx_issue_links_source_id ON issue_links(source_id);
CREATE INDEX idx_issue_links_target_id ON issue_links(target_id);

-- ── issues.parent_id: 구조적 parent-child 계층 (링크와 별개 메커니즘) ──────────────
-- 링크(issue_links)와 별개 메커니즘. 한 이슈는 최대 한 부모를 가지므로 issues 자기참조 컬럼으로 표현 (ADR).
-- nullable: 부모 없는 최상위 이슈는 NULL. 같은 BC 라 issues 자기참조 실 FK.
-- ON DELETE 기본(NO ACTION): 부모-자식은 구조적 계층이라 부모 삭제를 자식 자동 삭제로 전파하지 않는다
--   (prod 는 소프트 삭제라 FK 미발화. 하드 삭제 시도 시 자식이 있으면 거부되어 무결성 보호).
ALTER TABLE issues ADD COLUMN parent_id UUID NULL REFERENCES issues(id);
COMMENT ON COLUMN issues.parent_id IS '부모 이슈 (issues.id 자기참조). NULL=최상위. 구조적 계층 — 링크(issue_links)와 별개 (FR-LK-01).';

-- FK 인덱스 (DATA.md §7). 부모→자식 조회(서브태스크 목록)에 사용.
CREATE INDEX idx_issues_parent_id ON issues(parent_id);

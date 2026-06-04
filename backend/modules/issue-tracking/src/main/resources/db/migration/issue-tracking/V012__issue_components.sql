-- 이슈↔컴포넌트 다대다 연결 테이블 — FR-CM-02. 관계 테이블이라 소프트 삭제 없음(연결 해제 = 행 DELETE).

-- issue_components 연결(조인) 테이블
-- 한 이슈가 여러 컴포넌트에, 한 컴포넌트가 여러 이슈에 속하는 N:M 관계를 표현.
-- 같은 BC(issue-tracking) 내부 테이블이므로 issues / components 양쪽 실 FK 적용.
-- 소프트 삭제 미적용: 연결 자체는 도메인 엔티티가 아닌 순수 관계이므로 해제 = 행 DELETE (DATA.md §3 대상 아님).
CREATE TABLE issue_components (
    issue_id     UUID        NOT NULL REFERENCES issues(id),
    component_id UUID        NOT NULL REFERENCES components(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (issue_id, component_id)
);
COMMENT ON TABLE  issue_components              IS '이슈↔컴포넌트 N:M 연결. 관계 테이블이라 소프트 삭제 없음 (FR-CM-02).';
COMMENT ON COLUMN issue_components.issue_id     IS '연결된 이슈 (issues.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_components.component_id IS '연결된 컴포넌트 (components.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_components.created_at   IS '연결 생성 시각. TIMESTAMPTZ (DATA.md §4).';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함).
-- FK 는 issue_id / component_id 두 개지만 인덱스는 component_id 만 추가한다.
-- issue_id 는 복합 PK (issue_id, component_id) 의 선두 컬럼이라 PK 인덱스가 이미 커버하므로 중복 불요.
-- component_id 는 PK 후미 컬럼이라 단독 조회/조인(컴포넌트→이슈 역방향)에 활용 못 해 별도 인덱스가 필요.
CREATE INDEX idx_issue_components_component_id ON issue_components(component_id);

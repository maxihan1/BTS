-- boards / board_columns 테이블 생성 (FR-BD-01 V500) — 칸반 보드 Aggregate (보드 + 상태 1:1 매핑 컬럼)
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).

-- ── boards ────────────────────────────────────────────────────────────────────
-- 칸반 보드 Aggregate Root. 한 프로젝트에 여러 보드를 둘 수 있다(명시적 CRUD).
-- project_key(문자열)를 쓰는 이유: agile-planning BC 는 BC 격리상 issue-tracking 의 projects 테이블을
-- 직접 참조할 수 없어 projectKey→projectId(UUID) 변환이 불가하다. 따라서 FK 없이 문자열로 저장한다
-- (notification.notification_policies.project_key 선례, DATA.md §7 BC 격리).
CREATE TABLE boards (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key VARCHAR(64) NOT NULL,         -- BC 격리: FK 아님(issue-tracking projects.key 문자열)
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ NULL              -- 소프트 삭제(DATA.md §1.2). 삭제된 보드는 조회에서 제외(404)
);

COMMENT ON TABLE  boards             IS '칸반 보드 Aggregate — 프로젝트 이슈를 컬럼별로 시각화하는 작업 현황판';
COMMENT ON COLUMN boards.project_key IS 'issue-tracking projects.key 문자열(BC 격리, FK 아님)';
COMMENT ON COLUMN boards.deleted_at  IS '소프트 삭제 시각. NULL=활성. non-NULL=삭제됨(보드 조회 404)';

-- 프로젝트별 보드 목록 조회(GET /boards?projectKey=) 최적화 — 활성 보드만 인덱싱(부분 인덱스).
CREATE INDEX idx_boards_project_key ON boards (project_key) WHERE deleted_at IS NULL;

-- ── board_columns ───────────────────────────────────────────────────────────────
-- 보드 컬럼. 각 행이 워크플로우 상태 1개에 1:1 매핑된다(상태별 명시 매핑).
-- category 는 생성 시점 워크플로우 상태의 카테고리(TODO/IN_PROGRESS/DONE) 표시용 스냅샷이다.
-- (워크플로우 상태 변경 시 컬럼 재동기화는 이번 범위 제외 — 후속 FR.)
CREATE TABLE board_columns (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id      UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    state_key     VARCHAR(50) NOT NULL,       -- 매핑된 워크플로우 상태 키(project-workflow workflow_states.key)
    name          TEXT NOT NULL,
    category      VARCHAR(16) NOT NULL,       -- TODO/IN_PROGRESS/DONE (표시·그룹용 스냅샷)
    display_order INTEGER NOT NULL DEFAULT 0,
    -- 한 보드 안에서 같은 상태에 두 컬럼이 매핑되지 않도록 보장(상태 1:1 매핑).
    -- 이 복합 UNIQUE 인덱스의 leftmost prefix(board_id)가 board_id FK 조회·CASCADE 점검을 커버하므로
    -- board_id 전용 FK 인덱스를 따로 두지 않는다(중복 인덱스 회피, DATA.md §7 의도 충족).
    UNIQUE (board_id, state_key)
);

COMMENT ON TABLE  board_columns               IS '보드 컬럼 — 워크플로우 상태 1개에 1:1 매핑된 세로 열';
COMMENT ON COLUMN board_columns.state_key     IS '매핑된 워크플로우 상태 키(project-workflow workflow_states.key)';
COMMENT ON COLUMN board_columns.category      IS 'TODO/IN_PROGRESS/DONE — 생성 시점 워크플로우 카테고리 스냅샷(표시용)';
COMMENT ON COLUMN board_columns.display_order IS '컬럼 표시 순서(작을수록 왼쪽)';

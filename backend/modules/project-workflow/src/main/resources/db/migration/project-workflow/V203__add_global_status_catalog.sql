-- 전역 상태 카탈로그 2 테이블 (FR-WF-04) — 사이트 전역 statuses + 워크플로우 N:M workflow_statuses

-- ── statuses ──────────────────────────────────────────────────────────────────
-- 사이트 전역 상태 카탈로그. 지금까지 상태는 workflow_states 로 워크플로우에 종속됐으나,
-- issues.current_state_key 와 board_columns.state_key 가 FK 없이 key 문자열만 보고 있어
-- 런타임은 이미 상태 키를 전역 식별자처럼 쓰고 있었다. 스키마를 그 사용법에 맞춘다.
-- ADR 2026-08-18-workflow-global-status-catalog D1.
--
-- key 는 생성 시점에 확정하고 이후 불변이다(ADR D3). 두 소비자가 FK 없이 문자열로 참조하므로
-- key 를 바꾸면 DB 가 막아 주지 않은 채 이슈가 존재하지 않는 상태를 가리키게 된다.
CREATE TABLE statuses (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key         VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    category    TEXT         NOT NULL CHECK (category IN ('TODO', 'IN_PROGRESS', 'DONE')),
    is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

COMMENT ON TABLE  statuses             IS '사이트 전역 상태 카탈로그 — 워크플로우가 가져다 쓰는 상태의 정본';
COMMENT ON COLUMN statuses.key         IS '상태 식별 키. 생성 시점 확정 후 불변 (issues.current_state_key · board_columns.state_key 가 FK 없이 참조)';
COMMENT ON COLUMN statuses.name        IS '표시 이름. 자유롭게 바꿀 수 있다 — 소비자는 key 만 본다';
COMMENT ON COLUMN statuses.category    IS '이슈 상태 대분류: TODO, IN_PROGRESS, DONE';
COMMENT ON COLUMN statuses.is_system   IS '시스템 예약 상태 여부 (사용자가 지울 수 없음)';
COMMENT ON COLUMN statuses.deleted_at  IS '소프트 삭제 시각. NULL 이면 살아 있는 상태';

-- 이름은 대소문자를 무시하고 유일하다 (Jira Cloud 동일 · ADR D1).
-- 같은 이름의 상태가 둘이면 사용자가 전환을 걸 때 어느 쪽인지 구분할 수 없다.
CREATE UNIQUE INDEX uq_statuses_lower_name
    ON statuses (lower(name))
 WHERE deleted_at IS NULL;

-- ── workflow_statuses ─────────────────────────────────────────────────────────
-- 워크플로우 ↔ 상태 N:M. 워크플로우마다 다른 값(표시 순서 · 다이어그램 좌표)만 여기 둔다.
-- 이름과 카테고리는 전역이라 statuses 에 있다 — 이 분리가 ADR D2 의 실질이다.
--
-- status_id 는 RESTRICT 다. 어느 워크플로우가 쓰고 있는 상태는 카탈로그에서 지울 수 없다.
CREATE TABLE workflow_statuses (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id   UUID        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    status_id     UUID        NOT NULL REFERENCES statuses (id)  ON DELETE RESTRICT,
    display_order INTEGER     NOT NULL DEFAULT 0,
    layout_x      REAL,
    layout_y      REAL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workflow_id, status_id)
);

COMMENT ON TABLE  workflow_statuses               IS '워크플로우가 사용하는 상태 목록 — 전역 카탈로그와의 N:M 연결';
COMMENT ON COLUMN workflow_statuses.display_order IS 'UI 정렬 순서. 워크플로우마다 다르므로 전역 카탈로그가 아니라 여기 둔다';
COMMENT ON COLUMN workflow_statuses.layout_x      IS '다이어그램 편집기 노드 X 좌표. NULL 이면 자동 배치';
COMMENT ON COLUMN workflow_statuses.layout_y      IS '다이어그램 편집기 노드 Y 좌표. NULL 이면 자동 배치';

-- FK 인덱스 2개 (DATA.md §7 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다)
CREATE INDEX idx_workflow_statuses_workflow
    ON workflow_statuses (workflow_id);

CREATE INDEX idx_workflow_statuses_status
    ON workflow_statuses (status_id);

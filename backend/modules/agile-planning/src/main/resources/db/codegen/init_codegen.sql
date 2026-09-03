-- jOOQ 코드 생성용 초기화 SQL (agile-planning BC) — V500~V506 테이블 구조 미러 (시드 제외, codegen은 구조만 필요)
-- boards / board_columns DDL은 V500__boards.sql + V501__board_wip_swimlane.sql + V502__board_swimlane_epic.sql 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성).
-- sprints / sprint_issues DDL은 V503__sprints.sql 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성).
-- board_quick_filters DDL은 V504__board_quick_filters.sql 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성).
-- boards 의 board_type 은 V505__board_type.sql 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성).
-- sprints 의 board_id 는 V506__sprint_board_id.sql 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성).
-- ★ 이 정합은 이제 `scripts/workflow/codegen-mirror-parity.test.ts` 가 차집합으로 강제한다 — 주석에만 의존하지 않는다.

-- ── boards (V500 + V501 + V502 + V505 미러) ──────────────────────────────────────────────
CREATE TABLE boards (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key    VARCHAR(64) NOT NULL,
    name           TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ NULL,
    swimlane_field VARCHAR(16) NOT NULL DEFAULT 'NONE',  -- V502: 스윔레인 기준(NONE/ASSIGNEE/PRIORITY/EPIC)
    board_type     VARCHAR(16) NOT NULL DEFAULT 'KANBAN',  -- V505: 보드 종류(SCRUM/KANBAN)
    CONSTRAINT boards_swimlane_field_allowed CHECK (swimlane_field IN ('NONE', 'ASSIGNEE', 'PRIORITY', 'EPIC')),
    CONSTRAINT boards_board_type_allowed CHECK (board_type IN ('SCRUM', 'KANBAN'))
);

CREATE INDEX idx_boards_project_key ON boards (project_key) WHERE deleted_at IS NULL;

-- ── board_columns (V500 + V501 + V508 미러) ──────────────────────────────────────
CREATE TABLE board_columns (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id      UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    state_key     VARCHAR(50) NULL,  -- V508: 레거시 단일 매핑(정본은 board_column_states). 상태 0개 컬럼이면 NULL
    name          TEXT NOT NULL,
    category      VARCHAR(16) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    wip_limit     INTEGER NULL  -- V501: WIP 제한(NULL=무제한, 양수만)
        CONSTRAINT board_columns_wip_limit_positive CHECK (wip_limit IS NULL OR wip_limit > 0),
    UNIQUE (board_id, state_key),
    CONSTRAINT uq_board_columns_id_board UNIQUE (id, board_id)  -- V508: 복합 FK 참조 대상
);

-- ── board_column_states (V508 미러) ──────────────────────────────────────────────
-- 컬럼 ↔ 상태 키 1:N. 컬럼 하나가 상태 0개 이상을 담는다.
-- ★ 복합 FK (column_id, board_id) 가 「비정규화된 board_id 가 컬럼의 실제 소유 보드와 같다」를
--   DB 가 지게 한다. 그것이 없으면 UNIQUE(board_id, state_key) 가 엉뚱한 것을 지킨다.
CREATE TABLE board_column_states (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    column_id     UUID NOT NULL,
    board_id      UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    state_key     VARCHAR(50) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (board_id, state_key),
    UNIQUE (column_id, state_key),
    FOREIGN KEY (column_id, board_id) REFERENCES board_columns (id, board_id) ON DELETE CASCADE
);

-- ── sprints (V503 + V506 미러) ─────────────────────────────────────────────────────
CREATE TABLE sprints (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key VARCHAR(64) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    goal        TEXT,
    status      VARCHAR(16) NOT NULL DEFAULT 'PLANNED'
        CONSTRAINT sprints_status_allowed CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED')),
    start_date  DATE,
    end_date    DATE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ NULL,
    board_id    UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE  -- V506: 소속 보드
);

CREATE INDEX idx_sprints_project ON sprints (project_key) WHERE deleted_at IS NULL;
CREATE INDEX idx_sprints_board_active ON sprints (board_id) WHERE deleted_at IS NULL AND status = 'ACTIVE';

-- ── sprint_issues (V503 미러) ─────────────────────────────────────────────────────
CREATE TABLE sprint_issues (
    sprint_id  UUID NOT NULL REFERENCES sprints (id) ON DELETE CASCADE,
    issue_key  VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sprint_id, issue_key),
    CONSTRAINT sprint_issues_issue_key_unique UNIQUE (issue_key)
);

CREATE INDEX idx_sprint_issues_sprint ON sprint_issues (sprint_id);

-- ── board_quick_filters (V504 미러) ───────────────────────────────────────────────
CREATE TABLE board_quick_filters (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id   UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    query      TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_board_quick_filters_board_name UNIQUE (board_id, name)
);

CREATE INDEX idx_board_quick_filters_board ON board_quick_filters (board_id);

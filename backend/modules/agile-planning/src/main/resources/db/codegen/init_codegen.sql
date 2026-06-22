-- jOOQ 코드 생성용 초기화 SQL (agile-planning BC) — V500~V501 테이블 구조 미러 (시드 제외, codegen은 구조만 필요)
-- boards / board_columns DDL은 V500__boards.sql + V501__board_wip_swimlane.sql 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성).

-- ── boards (V500 + V501 미러) ────────────────────────────────────────────────────
CREATE TABLE boards (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key    VARCHAR(64) NOT NULL,
    name           TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ NULL,
    swimlane_field VARCHAR(16) NOT NULL DEFAULT 'NONE'  -- V501: 스윔레인 기준(NONE/ASSIGNEE/PRIORITY)
        CONSTRAINT boards_swimlane_field_allowed CHECK (swimlane_field IN ('NONE', 'ASSIGNEE', 'PRIORITY'))
);

CREATE INDEX idx_boards_project_key ON boards (project_key) WHERE deleted_at IS NULL;

-- ── board_columns (V500 + V501 미러) ─────────────────────────────────────────────
CREATE TABLE board_columns (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id      UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    state_key     VARCHAR(50) NOT NULL,
    name          TEXT NOT NULL,
    category      VARCHAR(16) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    wip_limit     INTEGER NULL  -- V501: WIP 제한(NULL=무제한, 양수만)
        CONSTRAINT board_columns_wip_limit_positive CHECK (wip_limit IS NULL OR wip_limit > 0),
    UNIQUE (board_id, state_key)
);

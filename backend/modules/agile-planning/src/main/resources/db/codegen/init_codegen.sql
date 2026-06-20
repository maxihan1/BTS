-- jOOQ 코드 생성용 초기화 SQL (agile-planning BC) — V500 테이블 구조만 미러 (시드 제외, codegen은 구조만 필요)
-- boards / board_columns DDL은 V500__boards.sql 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성).

-- ── boards (V500 미러) ──────────────────────────────────────────────────────────
CREATE TABLE boards (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key VARCHAR(64) NOT NULL,
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ NULL
);

CREATE INDEX idx_boards_project_key ON boards (project_key) WHERE deleted_at IS NULL;

-- ── board_columns (V500 미러) ─────────────────────────────────────────────────
CREATE TABLE board_columns (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id      UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    state_key     VARCHAR(50) NOT NULL,
    name          TEXT NOT NULL,
    category      VARCHAR(16) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    UNIQUE (board_id, state_key)
);

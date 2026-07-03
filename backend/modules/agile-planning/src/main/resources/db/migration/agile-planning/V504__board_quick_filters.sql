-- board_quick_filters 테이블 생성 (FR-UX-01 V504) — 보드별 저장된 이름 붙은 퀵 필터(보드 공유)
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).

-- ── board_quick_filters ────────────────────────────────────────────────────────
-- 퀵 필터. 보드 상단 칩 클릭 한 번으로 즉시 적용되는 이름 붙은 필터 조합(보드 공유, user_id 없음).
-- query 는 BoardCardFilter 쿼리스트링(assignee/label/component/unassigned 4종). 저장 전 파싱 검증 + 정규화(서비스 계층).
-- 보드에 1:N 종속 → board_id FK ON DELETE CASCADE (board_columns 선례, DATA.md §7 조인/자식 CASCADE).
-- OCC 미적용(단순 메타, last-write-wins) → version 컬럼 없음. 소프트 삭제 미적용(퀵필터는 즉시 물리 삭제).
CREATE TABLE board_quick_filters (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id   UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,               -- 표시 이름(칩 라벨). 같은 보드 내 UNIQUE
    query      TEXT NOT NULL,               -- BoardCardFilter 쿼리스트링(정규화 저장). 예: assignee=<uuid>&label=<name>
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 같은 보드 안에서 이름 중복 금지(EC2 409). name 은 NULL 불가라 NULLS NOT DISTINCT 불필요.
    CONSTRAINT uq_board_quick_filters_board_name UNIQUE (board_id, name)
);

COMMENT ON TABLE  board_quick_filters            IS '퀵 필터 — 보드별 저장된 이름 붙은 필터 조합, 칩 클릭 즉시 적용(보드 공유)';
COMMENT ON COLUMN board_quick_filters.name       IS '표시 이름(칩 라벨). 같은 보드 내 UNIQUE(board_id, name)';
COMMENT ON COLUMN board_quick_filters.query      IS 'BoardCardFilter 쿼리스트링(정규화 저장) — assignee/label/component/unassigned 4종';

-- board_id FK 조회·CASCADE 점검·보드별 퀵필터 목록 조회(created_at ASC) 최적화(FK 전용 인덱스, DATA.md §7).
-- UNIQUE(board_id, name) leftmost prefix(board_id)가 이를 커버하나, 명시 인덱스로 의도를 분명히 한다.
CREATE INDEX idx_board_quick_filters_board ON board_quick_filters (board_id);

-- board_columns.wip_limit / boards.swimlane_field 추가 (FR-BD-03 V501) — WIP 제한 + 스윔레인 설정
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).

-- ── board_columns.wip_limit (WIP 제한) ────────────────────────────────────────
-- 컬럼당 최대 카드 수. NULL = 제한 없음. 양수만 허용(0/음수 거부).
-- 백엔드는 경고 신호(wipExceeded)만 응답하고 카드 이동을 차단하지 않는다(ADR 결정 1).
ALTER TABLE board_columns ADD COLUMN wip_limit INTEGER NULL
    CONSTRAINT board_columns_wip_limit_positive CHECK (wip_limit IS NULL OR wip_limit > 0);

COMMENT ON COLUMN board_columns.wip_limit IS
    'WIP 제한 — 컬럼당 최대 카드 수. NULL=제한 없음, 양수만 허용(경고 전용, 이동 무차단)';

-- ── boards.swimlane_field (스윔레인 기준) ──────────────────────────────────────
-- 보드를 가로로 분리하는 기준. NONE=분리 없음 / ASSIGNEE=담당자별 / PRIORITY=우선순위별.
-- 백엔드는 설정값을 저장·echo 만 하고 실제 그룹핑(가로 분리)은 프론트가 수행한다(ADR 결정 2).
-- EPIC 은 FR-EP 미구현으로 enum 에서 제외한다(ADR 결정 3).
ALTER TABLE boards ADD COLUMN swimlane_field VARCHAR(16) NOT NULL DEFAULT 'NONE'
    CONSTRAINT boards_swimlane_field_allowed CHECK (swimlane_field IN ('NONE', 'ASSIGNEE', 'PRIORITY'));

COMMENT ON COLUMN boards.swimlane_field IS
    '스윔레인 기준 — NONE/ASSIGNEE/PRIORITY (그룹핑은 프론트, EPIC 은 FR-EP 로 이연)';

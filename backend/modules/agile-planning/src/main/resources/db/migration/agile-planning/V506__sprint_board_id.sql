-- sprints.board_id 추가 + 백필 (FR-BD-04 V506) — 스프린트를 보드 소속으로
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).
--
-- 설계 정본. docs/adr/2026-09-01-board-type-and-active-sprint.md (채택) D2.
-- Jira 에서 백로그와 스프린트는 **보드의 일부**다. 프로젝트에 보드가 여럿이면 백로그도 여럿이어야
-- 「다수 보드」가 의미를 갖는다 — 프로젝트 백로그 하나를 N개 보드가 공유하면 보드를 늘려도
-- 계획 단위가 안 늘어난다.

-- ── ① 컬럼 추가 (우선 nullable) ───────────────────────────────────────────────────
-- ON DELETE CASCADE 근거. BTS 의 보드 삭제는 소프트 삭제(deleted_at)라 실제로는 발동하지 않는다.
-- 그럼에도 두는 이유는 나중에 하드 삭제 경로가 생겼을 때 고아 스프린트를 남기지 않기 위함이다.
ALTER TABLE sprints
    ADD COLUMN board_id UUID REFERENCES boards (id) ON DELETE CASCADE;

COMMENT ON COLUMN sprints.board_id IS
    '소속 보드. Jira 는 스프린트가 보드(origin board)에 매달린다. 백필로 전 행이 채워지며 NOT NULL 이다.';

-- ── ② 스프린트를 가진 프로젝트마다 스크럼 보드 1개 신설 ──────────────────────────────
-- ★ 기존 보드를 SCRUM 으로 **승격하지 않는다.** 승격하면 그 보드의 카드가 활성 스프린트 것만 남아
--   사용자가 보던 것이 사라진다 (ADR 기각 대안 A-3). 새로 만들고 기존 칸반 보드는 손대지 않는다.
-- ★ soft-deleted 스프린트도 대상이다. NOT NULL 은 전 행에 걸리므로 「스프린트가 전부 삭제된
--   프로젝트」를 빼면 ⑤ 승격에서 죽는다.
INSERT INTO boards (project_key, name, board_type)
SELECT DISTINCT s.project_key, s.project_key || ' 스크럼 보드', 'SCRUM'
FROM sprints s;

-- ── ③ 컬럼 복제 — 그 프로젝트의 가장 오래된 활성 칸반 보드에서 ─────────────────────────
-- ★ 조회 경로에는 컬럼 시드가 없다. `BoardApplicationService` KDoc 이 조회를
--   "boards/board_columns 로드 → placeCards" 로 못박는다 — 컬럼 0개로 두면 영원히 빈 보드다.
--   복제할 보드가 없는 프로젝트(스프린트는 있는데 보드가 0개)는 애플리케이션 계층의
--   자가 치유(컬럼이 비면 워크플로우 카탈로그에서 시드)가 구제한다.
INSERT INTO board_columns (board_id, state_key, name, category, display_order, wip_limit)
SELECT nb.id, c.state_key, c.name, c.category, c.display_order, c.wip_limit
FROM boards nb
JOIN LATERAL (
    SELECT b.id
    FROM boards b
    WHERE b.project_key = nb.project_key
      AND b.board_type = 'KANBAN'
      AND b.deleted_at IS NULL
    ORDER BY b.created_at, b.id
    LIMIT 1
) src ON TRUE
JOIN board_columns c ON c.board_id = src.id
WHERE nb.board_type = 'SCRUM';

-- ── ④ 기존 스프린트를 신설 보드에 연결 ────────────────────────────────────────────────
-- 이 시점에 board_type='SCRUM' 인 보드는 ② 가 만든 것뿐이라 (project_key, SCRUM) 이 유일하다.
UPDATE sprints s
SET board_id = b.id
FROM boards b
WHERE b.project_key = s.project_key
  AND b.board_type = 'SCRUM';

-- ── ⑤ NOT NULL 승격 ────────────────────────────────────────────────────────────────
-- nullable 로 남기면 「보드 없는 스프린트」라는 두 번째 상태가 생겨 조회가 조용히 갈린다.
ALTER TABLE sprints
    ALTER COLUMN board_id SET NOT NULL;

-- ── ⑥ 활성 스프린트 부분 인덱스 ────────────────────────────────────────────────────
-- FR-BD-04 의 「보드당 활성 1개」 가드가 매 start 마다 "이 보드에 ACTIVE 가 있나" 를 묻는다.
-- COMPLETED 가 쌓일수록 커지는 전체 인덱스 대신 활성만 인덱싱한다.
CREATE INDEX idx_sprints_board_active ON sprints (board_id)
    WHERE deleted_at IS NULL AND status = 'ACTIVE';

-- ★ 기존 다중 ACTIVE 행을 깨지 않는다. PR #182 Deviation ⑤ 가 「동시 ACTIVE 다중 허용」을
--   명시적으로 결정했으므로 실재할 수 있다. ADR 이 그 결정을 무효화했지만 가드는 start 시점에만
--   건다 — 마이그레이션이 기존 데이터를 조용히 바꾸지 않는다.

-- 되돌리기.
--   ALTER TABLE sprints DROP COLUMN board_id;   (인덱스도 함께 사라진다)
-- ★ 비대칭 — ② 가 만든 스크럼 보드는 남는다. 되돌릴 때 소프트 삭제 대상으로 다뤄야 한다.

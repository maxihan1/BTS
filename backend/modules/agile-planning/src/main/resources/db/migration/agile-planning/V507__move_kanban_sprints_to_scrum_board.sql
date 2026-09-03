-- 칸반 보드에 소속된 스프린트를 그 프로젝트의 스크럼 보드로 이관 (부채 165 V507) — 데이터만 옮긴다
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).
--
-- 설계 정본. docs/specs/2026-09-03-kanban-sprint-move-and-lock-budget.md R1·R2·R7 /
-- docs/adr/2026-09-01-board-type-and-active-sprint.md D2.
--
-- 왜 필요한가. V506 ④ 는 **그때 존재하던** 스프린트를 전부 스크럼 보드에 붙였다. 그 뒤 스프린트 생성이
-- `boardId` 를 그대로 받아들이면서(SprintApplicationService.resolveTargetBoard) 칸반 보드에 매달린
-- 스프린트가 생겼고, 그런 스프린트는 백로그에도 보드에도 안 나온다 — 사용자에게는 그냥 사라진 것이다.
--
-- ★ DDL 은 건드리지 않는다. `idx_sprints_board_active`(V506 ⑥)를 UNIQUE 로 승격하지 않는다 —
--   V506:66-68 의 사유(PR #182 Deviation ⑤ · 기존 다중 ACTIVE 를 깨지 않는다)가 여전히 유효하다.
--   이 마이그레이션은 데이터를 정리하되 스키마로 유일성을 못박지는 않는다.

-- ── ① 이관 대상이 있는데 활성 스크럼 보드가 없는 프로젝트에 스크럼 보드 신설 + 컬럼 복제 ─────────
-- ★ 멱등(R7). 신설을 `NOT EXISTS` 로 감싼다. 부채 161 이 「V506 을 되돌렸다 재적용하면 스크럼 보드가
--   중복 생성된다」를 지적했다 — V507 이 같은 함정을 반복하면 안 된다. Flyway 는 같은 버전을 두 번
--   적용하지 않으므로 이 성질은 Flyway 밖에서만 잴 수 있다(스펙 E11 · KanbanSprintMoveMigrationTest).
-- ★ 스크럼 보드 존재 판정에 `deleted_at IS NULL` 을 건다. 소프트 삭제된 보드는 「있는 것」이 아니다 —
--   BoardRepository.findScrumBoardIdByProject(:256-266) 와 같은 규칙이라야 앱과 마이그레이션이 안 갈린다.
-- ★ 반대로 **칸반** 쪽 소속 판정(kb)에는 `deleted_at` 조건을 걸지 않는다. 삭제된 칸반 보드에 매달린
--   스프린트야말로 더 확실히 안 보이므로 이관 대상에서 빼면 부채 165 가 그대로 남는다.
-- ★ `s.deleted_at IS NULL` — 소프트 삭제된 스프린트는 대상이 아니다(스펙 E5). 되살릴 때 어느 보드에
--   있었는지가 바뀌면 삭제 전 상태로 복구되지 않는다.
-- ★ 보드 이름 `project_key || ' 스크럼 보드'` 는 이제 **세 번째 사본**이다 (부채 162).
--   V506:24 · BoardApplicationService.kt:199(ensureScrumBoard) · 여기. 셋이 갈리면 마이그레이션이 만든
--   보드와 앱이 만든 보드가 한 프로젝트에 이름만 다르게 둘 생긴다.
--
-- ★ CTE + RETURNING 을 쓰는 이유 — V506 ③ 은 `WHERE nb.board_type = 'SCRUM'` 로 충분했다. 그 시점의
--   스크럼 보드는 전부 ② 가 방금 만든 것뿐이었기 때문이다. V507 은 다르다. **컬럼을 이미 가진 기존
--   스크럼 보드**가 공존하므로 같은 조건을 쓰면 그 보드에 컬럼이 겹쳐 쌓이고, UNIQUE(board_id,
--   state_key) 에 걸려 마이그레이션째 실패한다. RETURNING 으로 신설분만 정확히 집는다.
WITH new_scrum_boards AS (
    INSERT INTO boards (project_key, name, board_type)
    SELECT DISTINCT s.project_key, s.project_key || ' 스크럼 보드', 'SCRUM'
    FROM sprints s
    JOIN boards kb ON kb.id = s.board_id
    WHERE kb.board_type = 'KANBAN'
      AND s.deleted_at IS NULL
      AND NOT EXISTS (
          SELECT 1
          FROM boards sb
          WHERE sb.project_key = s.project_key
            AND sb.board_type = 'SCRUM'
            AND sb.deleted_at IS NULL
      )
    RETURNING id, project_key
)
-- 컬럼 복제는 V506 ③ 형태 그대로 — 그 프로젝트의 **가장 오래된 활성 칸반 보드**에서 LATERAL 로 가져온다.
-- 조회 경로에는 컬럼 시드가 없다(V506:27-31) — 0개로 두면 영원히 빈 보드다. 복제할 칸반이 없는 경우는
-- 애플리케이션 계층의 자가 치유(컬럼이 비면 워크플로우 카탈로그에서 시드)가 구제한다.
-- ORDER BY 는 `(created_at, id)` — created_at 동점이면 id 로 가른다. created_at 만 쓰면 어느 보드가
-- 복제 소스인지가 실행마다 갈린다(BoardRepository.kt:263-265 가 같은 규칙에 같은 주석을 단다).
INSERT INTO board_columns (board_id, state_key, name, category, display_order, wip_limit)
SELECT nb.id, c.state_key, c.name, c.category, c.display_order, c.wip_limit
FROM new_scrum_boards nb
JOIN LATERAL (
    SELECT b.id
    FROM boards b
    WHERE b.project_key = nb.project_key
      AND b.board_type = 'KANBAN'
      AND b.deleted_at IS NULL
    ORDER BY b.created_at, b.id
    LIMIT 1
) src ON TRUE
JOIN board_columns c ON c.board_id = src.id;

-- ── ② 칸반 소속 스프린트를 그 프로젝트의 활성 스크럼 보드로 옮긴다 ─────────────────────────────
-- 목적지 선정도 `(created_at, id)` 다 — findScrumBoardIdByProject 와 같은 규칙이라야 「이 프로젝트의
-- 스크럼 보드」가 앱과 마이그레이션에서 같은 보드를 가리킨다(스펙 E3 · 스크럼 보드가 둘 이상인 경우).
-- ★ LATERAL 이 0행이면 그 스프린트는 조인에서 통째로 빠져 **갱신되지 않는다**. board_id 는 NOT NULL
--   이므로 상관 서브쿼리로 짜면 NULL 대입이 되어 마이그레이션이 죽는다 — 조인 형태가 안전판을 겸한다.
--   ① 이 대상 프로젝트마다 보드를 보장하므로 실제로 0행이 되는 경로는 없다.
-- ★ `board_id` 만 바꾼다. version·updated_at 은 건드리지 않는다(V506 ④ 와 동일) — 이관은 사용자의
--   편집이 아니라 데이터 정정이다. 상태 조정(ACTIVE → PLANNED)은 별건이다(스펙 R4·R5).
-- ★ COMPLETED 스프린트도 옮긴다(스펙 E4). 상태와 무관하게 「어느 보드의 백로그에 속하나」의 문제다.
WITH move_target AS (
    SELECT s.id AS sprint_id, tb.id AS board_id
    FROM sprints s
    JOIN boards kb ON kb.id = s.board_id
    JOIN LATERAL (
        SELECT b.id
        FROM boards b
        WHERE b.project_key = s.project_key
          AND b.board_type = 'SCRUM'
          AND b.deleted_at IS NULL
        ORDER BY b.created_at, b.id
        LIMIT 1
    ) tb ON TRUE
    WHERE kb.board_type = 'KANBAN'
      AND s.deleted_at IS NULL
)
UPDATE sprints s
SET board_id = t.board_id
FROM move_target t
WHERE t.sprint_id = s.id;

-- 되돌리기.
-- ★ 자동 원복 경로가 없다. 이관은 원래 board_id 를 어디에도 기록하지 않으므로 「어느 칸반에서 왔는지」를
--   되살릴 수 없다. 되돌려야 한다면 백업 복구가 유일한 수단이다(DATA.md 백업/복구 절차).
-- ★ ① 이 만든 스크럼 보드는 남는다 — V506 과 같은 비대칭이다. 소프트 삭제 대상으로 다뤄야 한다.

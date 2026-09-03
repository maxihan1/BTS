-- 칸반 보드에 소속된 스프린트를 그 프로젝트의 스크럼 보드로 이관 (부채 165 V507) — 데이터만 옮긴다
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).

-- ── ① 이관 대상이 있는데 활성 스크럼 보드가 없는 프로젝트에 스크럼 보드 신설 + 컬럼 복제 ──
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

-- ── ② 칸반 소속 스프린트를 그 프로젝트의 활성 스크럼 보드로 옮긴다 ──
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

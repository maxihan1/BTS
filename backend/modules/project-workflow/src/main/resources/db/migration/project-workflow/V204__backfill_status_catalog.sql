-- workflow_states → statuses 승격 백필 (FR-WF-04) — 유일성 가드 3종을 먼저 통과해야 한다

-- ── 가드 3종 ──────────────────────────────────────────────────────────────────
-- 왜 실패시키는가.
--   전역 카탈로그는 key 하나에 이름·카테고리가 하나여야 성립한다. 조용히 한쪽을 골라 나머지를
--   버리면 어느 워크플로우의 상태 이름이 말없이 바뀌고, 그 사실을 아무도 모른 채 배포가 끝난다.
--   배포를 멈추는 편이 데이터를 뭉개는 것보다 싸다 (ADR 2026-08-18-workflow-global-status-catalog D4).
--
--   시드 4종 실측으로는 위반이 0건이지만 **그 실측을 가정하지 않는다.** 배포된 DB 는 시드와 다를 수
--   있고, 가정에 기대는 백필은 조용히 데이터를 뭉갠다. 가드가 실제로 도는지는 위반 데이터를 심어
--   red 를 1회 확인했다 (`V203ToV205MigrationTest` 가드 3종).
DO $$
DECLARE
    v_conflicting_keys  TEXT;
    v_conflicting_names TEXT;
    v_long_keys         TEXT;
BEGIN
    -- ① key → (name, category) 가 1:1 이 아니면 승격할 수 없다.
    SELECT string_agg(key, ', ' ORDER BY key)
      INTO v_conflicting_keys
      FROM (
            SELECT key
              FROM workflow_states
             GROUP BY key
            HAVING COUNT(DISTINCT (name, category)) > 1
           ) AS conflicting;

    IF v_conflicting_keys IS NOT NULL THEN
        RAISE EXCEPTION
            '상태 키가 워크플로우마다 다른 이름/카테고리를 갖는다: %. '
            '전역 카탈로그로 승격할 수 없다 — workflow_states 에서 먼저 일치시켜라.',
            v_conflicting_keys;
    END IF;

    -- ② 서로 다른 key 가 같은 이름(대소문자 무시)을 쓰면 statuses 의 lower(name) 유일 인덱스에 걸린다.
    --    가드가 없으면 인덱스가 원시 오류로 배포를 멈추는데, 그 메시지는 원인을 말해주지 않는다.
    SELECT string_agg(DISTINCT lower(name), ', ')
      INTO v_conflicting_names
      FROM workflow_states
     WHERE lower(name) IN (
            SELECT lower(name)
              FROM workflow_states
             GROUP BY lower(name)
            HAVING COUNT(DISTINCT key) > 1
           );

    IF v_conflicting_names IS NOT NULL THEN
        RAISE EXCEPTION
            '서로 다른 상태 키가 같은 이름을 쓴다: %. '
            'statuses 는 lower(name) 이 유일해야 한다 — 이름을 갈라 놓아라.',
            v_conflicting_names;
    END IF;

    -- ③ workflow_states.key 는 TEXT 지만 statuses.key 는 VARCHAR(50) 이다 (소비자
    --    issues.current_state_key 와 폭을 맞춘다). 넘치면 사유를 말하고 멈춘다.
    SELECT string_agg(DISTINCT key, ', ')
      INTO v_long_keys
      FROM workflow_states
     WHERE length(key) > 50;

    IF v_long_keys IS NOT NULL THEN
        RAISE EXCEPTION
            '상태 키가 50자를 넘는다: %. statuses.key 는 VARCHAR(50) 이다.',
            v_long_keys;
    END IF;
END $$;

-- ── 백필 ──────────────────────────────────────────────────────────────────────
-- INSERT 만 한다. 기존 행을 지우거나 고치지 않는다 — workflow_states 는 DROP 시점(로드맵 마지막 PR)까지
-- workflow_transitions 의 FK 대상으로 살아 있어야 한다 (DATA.md §4 add → backfill → drop 3단 분할).

-- ① 전역 카탈로그 승격. 가드 ①을 통과했으므로 key 당 (name, category) 는 하나뿐이다.
INSERT INTO statuses (key, name, category, is_system)
SELECT DISTINCT ON (key) key, name, category, FALSE
  FROM workflow_states
 ORDER BY key
    ON CONFLICT (key) DO NOTHING;

-- ② 워크플로우 ↔ 상태 연결. display_order 는 워크플로우마다 다른 값이라 그대로 옮긴다.
INSERT INTO workflow_statuses (workflow_id, status_id, display_order)
SELECT ws.workflow_id, s.id, ws.display_order
  FROM workflow_states ws
  JOIN statuses s ON s.key = ws.key
    ON CONFLICT (workflow_id, status_id) DO NOTHING;

-- 구형 workflow_states 와 전환의 구 컬럼을 지우고 신 컬럼에 NOT NULL · CHECK 를 건다 (FR-WF-04 WF-04-4)

-- ## add → backfill → drop 의 3단 (DATA.md §4)
--
--   1단 add       V203 카탈로그 · V207 from_status_id·to_status_id 추가
--   2단 backfill  V204 카탈로그 백필 · V207 ⑤ 전환 재지정
--   3단 drop      구 컬럼 · workflow_states DROP + 신 컬럼 NOT NULL · CHECK   ← 여기
--
-- 읽기·쓰기 코드가 전부 신 컬럼으로 옮겨간 뒤다. 이 PR 이 마지막 폴백(WorkflowRepository ·
-- TransitionKeyResolver)과 마지막 구 테이블 쓰기(YamlSeedService · 복제)를 함께 지운다.
--
-- V207 이 이연한 두 제약(⑦ to_status_id NOT NULL · ⑪ ck_transition_kind_from)을 여기서 건다.

-- ── ① 재백필 — V207 뒤에 구 컬럼만 채워 들어온 행 ─────────────────────────────
-- V207 ⑤ 는 「그 시점에 있던 행」만 옮겼다. 그 뒤 구 컬럼으로만 쓰던 경로가 넣은 행은 신 컬럼이
-- NULL 이다. 같은 다리(workflow_states.key → statuses.key → 그 워크플로우의 workflow_statuses)로 잇는다.
-- 신 컬럼이 이미 찬 행은 건드리지 않는다 — 그쪽이 정본이다.
-- kind 는 거르지 않는다. GLOBAL·INITIAL 인데 구 from_state_id 가 남은 행은 여기서 from_status_id 를
-- 얻어 ② 에 걸린다 — 운영 쓰기 경로로는 생기지 않는 모양이라 조용히 고치지 않고 배포를 멈추게 둔다.
UPDATE workflow_transitions t
   SET from_status_id = ws.id
  FROM workflow_states st
  JOIN statuses          s  ON s.key = st.key AND s.deleted_at IS NULL
  JOIN workflow_statuses ws ON ws.status_id = s.id
 WHERE st.id = t.from_state_id
   AND ws.workflow_id = t.workflow_id
   AND t.from_status_id IS NULL;

UPDATE workflow_transitions t
   SET to_status_id = ws.id
  FROM workflow_states st
  JOIN statuses          s  ON s.key = st.key AND s.deleted_at IS NULL
  JOIN workflow_statuses ws ON ws.status_id = s.id
 WHERE st.id = t.to_state_id
   AND ws.workflow_id = t.workflow_id
   AND t.to_status_id IS NULL;

-- ── ② 가드 — 옮기지 못한 행이 있으면 배포를 멈춘다 ────────────────────────────
-- 구 컬럼은 이 마이그레이션에서 사라진다. 옮기지 못한 채 지우면 그 전환은 출발·도착을 영영 잃는다.
-- ⑤ 의 NOT NULL · CHECK 가 원인을 말하지 않는 오류로 죽기 전에 여기서 전환 이름을 들고 멈춘다.
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    SELECT string_agg(name || ' (' || id || ')', ', ')
      INTO v_bad
      FROM workflow_transitions
     WHERE to_status_id IS NULL
        OR (kind = 'NORMAL' AND from_status_id IS NULL)
        OR (kind IN ('GLOBAL', 'INITIAL') AND from_status_id IS NOT NULL);

    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION
            'V210. 신 컬럼으로 옮기지 못한 전환이 있다: %. '
            '구 컬럼이 가리키는 상태가 그 워크플로우의 workflow_statuses 에 없거나(편성에서 뺐거나 '
            'statuses 가 소프트 삭제됨) kind 와 출발 상태가 어긋난다 — 고친 뒤 다시 배포할 것.',
            v_bad;
    END IF;
END $$;

-- ── ③ 구 컬럼 DROP ─────────────────────────────────────────────────────────────
-- FK 와 인덱스(idx_workflow_transitions_from · _to)는 컬럼과 함께 사라진다.
ALTER TABLE workflow_transitions
    DROP COLUMN from_state_id,
    DROP COLUMN to_state_id;

-- ── ④ 구 테이블 DROP ───────────────────────────────────────────────────────────
-- 이제 이 표를 가리키는 FK 가 없다. CASCADE 를 쓰지 않는다 — 남은 참조가 있으면 조용히 끊지 말고 실패해야 한다.
DROP TABLE workflow_states;

-- ── ⑤ V207 이 이연한 제약 (⑦ · ⑪) ─────────────────────────────────────────────
ALTER TABLE workflow_transitions
    ALTER COLUMN to_status_id SET NOT NULL;

ALTER TABLE workflow_transitions
    ADD CONSTRAINT ck_transition_kind_from CHECK (
        (kind = 'NORMAL' AND from_status_id IS NOT NULL)
        OR (kind IN ('GLOBAL', 'INITIAL') AND from_status_id IS NULL)
    );

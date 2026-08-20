-- 전환 identity 를 (from,to) 2튜플에서 전환 ID 로 옮긴다 (FR-WF-05) — 다중 전환 · 전역 전환 · 최초 전환

-- ## 무엇이 문제였나 — 전환을 두 상태의 쌍으로만 부를 수 있었다
--
--   [V206 까지]                              [V207 이후]
--   UNIQUE (workflow, from, to)              전환 ID(UUID)가 1급 식별자
--   from_state_id NOT NULL                   kind 로 NORMAL/GLOBAL/INITIAL 을 가른다
--         ↑ 같은 상태쌍에 전환 1개                  ↑ 여럿 · 출발지 없는 전환도 표현된다
--
-- 전환을 (from, to) 로 식별하면 ① 같은 두 상태 사이에 이름이 다른 전환을 둘 이상 둘 수 없고
-- ② 시작 상태가 없는 전환(전역 전환)을 아예 적을 수 없다. identity 를 이미 있던 `id` 컬럼으로
-- 옮기고 제약을 그에 맞게 고쳐 두 가지를 함께 연다.
-- 설계 정본은 ADR 2026-08-18-workflow-transition-id-identity (D1~D4).
--
-- ## ★ 왜 구 컬럼을 DROP 하지 않는가
--
-- `from_state_id`·`to_state_id` 와 `workflow_states` 테이블은 **이 마이그레이션에서 살려 둔다.**
-- DATA.md §4 의 add → backfill → drop 3단 분할에서 이 PR 은 **1·2 단만** 한다.
--
--   1단 add       새 컬럼(from_status_id·to_status_id)을 NULL 허용으로 추가   ← 여기
--   2단 backfill  구 컬럼의 값을 새 컬럼으로 옮기고 NOT NULL 로 승격          ← 여기
--   3단 drop      읽기·쓰기가 전부 새 컬럼으로 옮겨간 뒤 구 컬럼을 떨어뜨린다  ← 로드맵 마지막 PR
--
-- 3단을 같은 PR 에서 하면 롤백할 자리가 없어진다. 배포 직후 읽기 경로에 문제가 드러나도 되돌릴
-- 값이 이미 사라진 뒤다. 구 컬럼이 남아 있는 동안에는 마이그레이션을 되돌려도 데이터가 온전하다.
-- 대신 NOT NULL 만 푼다(⑧) — 새로 생기는 GLOBAL·INITIAL 전환은 구 컬럼을 채우지 않기 때문이다.
--
-- ## 인덱스를 CONCURRENTLY 로 만들지 않는 이유
-- Flyway 트랜잭션 안에서는 쓸 수 없고 대상 테이블이 소규모다 (V203·V206 과 같은 판단 ·
-- ADR 2026-05-21-v001-initial-schema-non-concurrent).

-- ── ① 다중 전환 허용 — (workflow, from, to) UNIQUE 해제 ───────────────────────
-- 같은 두 상태 사이에 이름이 다른 전환을 두려면 이 제약이 먼저 없어져야 한다.
-- 제약 이름이 기대와 다르면 조용히 지나가지 않도록 DO 블록으로 확인 후 DROP 한다 (V204·V206 관례).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE table_name = 'workflow_transitions'
           AND constraint_name = 'workflow_transitions_workflow_id_from_state_id_to_state_id_key'
    ) THEN
        RAISE EXCEPTION
            'V207. workflow_transitions_workflow_id_from_state_id_to_state_id_key 제약이 없다. '
            'V200 의 UNIQUE(workflow_id, from_state_id, to_state_id) 선언이 바뀌었는지 확인할 것';
    END IF;
END $$;

ALTER TABLE workflow_transitions
    DROP CONSTRAINT workflow_transitions_workflow_id_from_state_id_to_state_id_key;

-- ── ② kind 도입 — 전환 3종을 가른다 ──────────────────────────────────────────
-- 기존 행은 전부 출발지가 있는 보통 전환이므로 DEFAULT 'NORMAL' 이 곧 올바른 백필이다.
-- PostgreSQL 11+ 는 DEFAULT 있는 컬럼 추가에서 테이블을 재작성하지 않는다 (DATA.md §4).
ALTER TABLE workflow_transitions
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'NORMAL';

ALTER TABLE workflow_transitions
    ADD CONSTRAINT ck_workflow_transitions_kind CHECK (kind IN ('NORMAL', 'GLOBAL', 'INITIAL'));

COMMENT ON COLUMN workflow_transitions.kind
    IS '전환 종류. NORMAL = 출발지 있는 보통 전환 · GLOBAL = 어느 상태에서든 · INITIAL = 이슈 생성 진입(워크플로우당 1개)';

-- ── ③ workflow_statuses 참조 컬럼 추가 (3단 분할의 1단) ──────────────────────
-- 전환의 출발·도착을 워크플로우 종속 workflow_states 가 아니라 전역 카탈로그 편성
-- workflow_statuses 로 재지정한다. 지금은 NULL 허용으로 열어 두고 ⑤에서 백필한다.
--
-- ON DELETE CASCADE 는 구 컬럼(V200 의 from_state_id·to_state_id)과 **같은 규칙**이다.
-- 두 세대가 공존하는 동안 삭제 동작이 컬럼마다 다르면, 상태를 지웠을 때 한쪽은 전환을 지우고
-- 다른 쪽은 막아 결과가 어느 FK 가 먼저 걸리느냐에 좌우된다.
ALTER TABLE workflow_transitions
    ADD COLUMN from_status_id UUID    NULL REFERENCES workflow_statuses (id) ON DELETE CASCADE,
    ADD COLUMN to_status_id   UUID    NULL REFERENCES workflow_statuses (id) ON DELETE CASCADE,
    ADD COLUMN display_order  INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN workflow_transitions.from_status_id
    IS '전환 출발 상태 FK (workflow_statuses). GLOBAL·INITIAL 은 NULL — 출발지가 없다는 것이 그 두 종류의 정의다';
COMMENT ON COLUMN workflow_transitions.to_status_id
    IS '전환 도착 상태 FK (workflow_statuses). 도착지 없는 전환은 어느 종류에도 없다';
COMMENT ON COLUMN workflow_transitions.display_order
    IS '편집기 표시 순서. INITIAL 은 0, 나머지는 워크플로우 안에서 1..n';

-- FK 인덱스 2개 (DATA.md §7 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다)
CREATE INDEX idx_workflow_transitions_from_status ON workflow_transitions (from_status_id);
CREATE INDEX idx_workflow_transitions_to_status   ON workflow_transitions (to_status_id);

-- ── ④ 백필 가드 — 대응 workflow_statuses 행이 없으면 배포를 멈춘다 ───────────
-- V204 가 세운 관례다. 가드 없이 진행하면 대응을 못 찾은 전환이 NULL 로 남고, ⑦의 NOT NULL
-- 승격이 원인을 말하지 않는 오류로 죽는다. 더 나쁜 경우는 그 전환이 새 읽기 경로에서 조용히
-- 사라지는 것이다 — 배포를 멈추는 편이 데이터를 뭉개는 것보다 싸다.
--
-- statuses 를 deleted_at IS NULL 로 좁히는 것은 두 가지 이유다. ① uq_statuses_key 가 부분
-- 유니크(V206)라 같은 key 의 소프트 삭제 행이 함께 있을 수 있어 조인이 갈라진다 ② 읽기 경로
-- (WorkflowRepository 의 STATUSES.DELETED_AT.isNull)와 같은 집합을 봐야 한다.
DO $$
DECLARE
    v_unmapped TEXT;
BEGIN
    SELECT string_agg(DISTINCT t.name, ', ')
      INTO v_unmapped
      FROM workflow_transitions t
      LEFT JOIN workflow_states   fs  ON fs.id = t.from_state_id
      LEFT JOIN statuses          fst ON fst.key = fs.key AND fst.deleted_at IS NULL
      LEFT JOIN workflow_statuses fws ON fws.workflow_id = t.workflow_id AND fws.status_id = fst.id
      LEFT JOIN workflow_states   ts  ON ts.id = t.to_state_id
      LEFT JOIN statuses          tst ON tst.key = ts.key AND tst.deleted_at IS NULL
      LEFT JOIN workflow_statuses tws ON tws.workflow_id = t.workflow_id AND tws.status_id = tst.id
     WHERE fws.id IS NULL OR tws.id IS NULL;

    IF v_unmapped IS NOT NULL THEN
        RAISE EXCEPTION
            '전환의 출발/도착 상태에 대응하는 workflow_statuses 행이 없다: %. '
            'V204 카탈로그 백필이 건너뛴 상태이거나 statuses 가 소프트 삭제된 것이다 — '
            '재지정 없이 진행하면 그 전환이 조용히 끊어진다.',
            v_unmapped;
    END IF;
END $$;

-- ── ⑤ 백필 — 구 FK 를 전역 카탈로그 편성으로 재지정 (3단 분할의 2단) ─────────
-- workflow_states.key → statuses.key → 그 워크플로우의 workflow_statuses 행으로 잇는다.
-- key 가 두 세대를 잇는 다리다 (V204 가 key 를 기준으로 카탈로그를 승격했다).
UPDATE workflow_transitions t
   SET from_status_id = ws.id
  FROM workflow_states st
  JOIN statuses          s  ON s.key = st.key AND s.deleted_at IS NULL
  JOIN workflow_statuses ws ON ws.status_id = s.id
 WHERE st.id = t.from_state_id
   AND ws.workflow_id = t.workflow_id;

UPDATE workflow_transitions t
   SET to_status_id = ws.id
  FROM workflow_states st
  JOIN statuses          s  ON s.key = st.key AND s.deleted_at IS NULL
  JOIN workflow_statuses ws ON ws.status_id = s.id
 WHERE st.id = t.to_state_id
   AND ws.workflow_id = t.workflow_id;

-- ── ⑥ display_order 백필 — DEFAULT 0 만 두면 기존 순서가 전부 뭉개진다 ───────
-- 화면(로드맵 PR 8 의 워크플로우 편집기)이 생기는 순간 「전환 순서가 뒤죽박죽」으로 드러나는
-- 조용한 실패다. 지금은 화면이 없어 보이지 않으므로 여기서 못을 박는다.
-- created_at 이 같은 트랜잭션에서 심긴 행들은 동률이라 name 을 2차 기준으로 둬 순서를 확정한다.
UPDATE workflow_transitions t
   SET display_order = s.rn
  FROM (
        SELECT id,
               row_number() OVER (PARTITION BY workflow_id ORDER BY created_at, name) AS rn
          FROM workflow_transitions
       ) s
 WHERE t.id = s.id;

-- ── ⑦ to_status_id NOT NULL 승격 ────────────────────────────────────────────
-- ④ 의 가드를 통과했으므로 남은 NULL 이 없다. 도착지 없는 전환은 어느 종류에도 없다.
ALTER TABLE workflow_transitions
    ALTER COLUMN to_status_id SET NOT NULL;

-- ── ⑧ 구 FK 의 NOT NULL 완화 — 컬럼은 지우지 않는다 (★ 위 「왜 DROP 하지 않는가」) ──
-- 이제부터 만들어지는 GLOBAL·INITIAL 전환은 구 컬럼을 채우지 않는다. 출발지가 없거나
-- (GLOBAL·INITIAL) 새 카탈로그에만 있는 상태를 가리키기 때문이다 — #393 의 워크플로우 생성
-- API 는 workflow_statuses 만 심고 workflow_states 는 만들지 않는다.
-- 기존 행의 값은 그대로 남아 3단(DROP)까지 롤백 자리를 지킨다.
ALTER TABLE workflow_transitions
    ALTER COLUMN from_state_id DROP NOT NULL,
    ALTER COLUMN to_state_id   DROP NOT NULL;

-- ── ⑨ INITIAL 백필 — 워크플로우별 display_order 최소 상태를 도착지로 1건씩 ───
-- 종전 시작 상태 해석은 WorkflowKeyResolverImpl 의 `minByOrNull { it.displayOrder }` 였다.
-- 그 동작을 **그대로 행으로 굳혀야** 관리자가 상태 순서를 바꿔도 이슈 생성 진입 상태가 흔들리지
-- 않는다 (ADR §맥락 이 닫으려는 결함이 정확히 그것이다). 지금 다른 상태를 고르면 이 마이그레이션
-- 자체가 기존 워크플로우의 생성 동작을 조용히 바꾼다.
--
-- display_order 는 0 — 생성 시점의 전환이라 편집기에서 항상 맨 앞이다. 기존 전환은 ⑥에서 1..n 을
-- 받았으므로 워크플로우 안에서 순서가 겹치지 않는다.
-- 상태가 하나도 없는 워크플로우는 도착지가 없어 자연히 제외된다 (Task 7 의 폴백이 받는다).
INSERT INTO workflow_transitions (workflow_id, kind, name, from_status_id, to_status_id, display_order)
SELECT first_status.workflow_id, 'INITIAL', '이슈 생성', NULL, first_status.id, 0
  FROM (
        SELECT DISTINCT ON (ws.workflow_id) ws.workflow_id, ws.id
          FROM workflow_statuses ws
          JOIN statuses s ON s.id = ws.status_id AND s.deleted_at IS NULL
         ORDER BY ws.workflow_id, ws.display_order, ws.id
       ) AS first_status;

-- ── ⑩ 부분 유니크 — 워크플로우당 INITIAL 1개 (F4) ───────────────────────────
CREATE UNIQUE INDEX uq_workflow_transitions_initial
    ON workflow_transitions (workflow_id)
 WHERE kind = 'INITIAL';

COMMENT ON INDEX uq_workflow_transitions_initial
    IS '워크플로우당 최초 전환은 1개다. 둘이면 이슈 생성 진입 상태가 어느 쪽인지 정할 수 없다';

-- ── ⑪ 무결성 가드 — kind 와 from_status_id 의 조합 ──────────────────────────
-- ⑤ 백필과 ⑨ INITIAL 삽입이 모두 끝난 뒤에 건다. 순서를 앞당기면 아직 from_status_id 가
-- NULL 인 기존 NORMAL 행 때문에 제약 추가 자체가 실패한다.
ALTER TABLE workflow_transitions
    ADD CONSTRAINT ck_transition_kind_from
    CHECK ((kind = 'NORMAL' AND from_status_id IS NOT NULL)
        OR (kind IN ('GLOBAL', 'INITIAL') AND from_status_id IS NULL));

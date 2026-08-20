-- 전환 identity 를 (from,to) 2튜플에서 전환 ID 로 옮긴다 (FR-WF-05) — 다중 전환 · 전역 전환 · 최초 전환

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

ALTER TABLE workflow_transitions
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'NORMAL';

ALTER TABLE workflow_transitions
    ADD CONSTRAINT ck_workflow_transitions_kind CHECK (kind IN ('NORMAL', 'GLOBAL', 'INITIAL'));

COMMENT ON COLUMN workflow_transitions.kind
    IS '전환 종류. NORMAL = 출발지 있는 보통 전환 · GLOBAL = 어느 상태에서든 · INITIAL = 이슈 생성 진입(워크플로우당 1개)';

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

CREATE INDEX idx_workflow_transitions_from_status ON workflow_transitions (from_status_id);
CREATE INDEX idx_workflow_transitions_to_status   ON workflow_transitions (to_status_id);

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

UPDATE workflow_transitions t
   SET display_order = s.rn
  FROM (
        SELECT id,
               row_number() OVER (PARTITION BY workflow_id ORDER BY created_at, name) AS rn
          FROM workflow_transitions
       ) s
 WHERE t.id = s.id;

ALTER TABLE workflow_transitions
    ALTER COLUMN to_status_id SET NOT NULL;

ALTER TABLE workflow_transitions
    ALTER COLUMN from_state_id DROP NOT NULL,
    ALTER COLUMN to_state_id   DROP NOT NULL;

INSERT INTO workflow_transitions (workflow_id, kind, name, from_status_id, to_status_id, display_order)
SELECT first_status.workflow_id, 'INITIAL', '이슈 생성', NULL, first_status.id, 0
  FROM (
        SELECT DISTINCT ON (ws.workflow_id) ws.workflow_id, ws.id
          FROM workflow_statuses ws
          JOIN statuses s ON s.id = ws.status_id AND s.deleted_at IS NULL
         ORDER BY ws.workflow_id, ws.display_order, ws.id
       ) AS first_status;

CREATE UNIQUE INDEX uq_workflow_transitions_initial
    ON workflow_transitions (workflow_id)
 WHERE kind = 'INITIAL';

COMMENT ON INDEX uq_workflow_transitions_initial
    IS '워크플로우당 최초 전환은 1개다. 둘이면 이슈 생성 진입 상태가 어느 쪽인지 정할 수 없다';

ALTER TABLE workflow_transitions
    ADD CONSTRAINT ck_transition_kind_from
    CHECK ((kind = 'NORMAL' AND from_status_id IS NOT NULL)
        OR (kind IN ('GLOBAL', 'INITIAL') AND from_status_id IS NULL));

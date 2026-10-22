-- 전환 화면 필드를 둔다 (FR-WF-01 D10 · WF-01-1c) — 전환마다 입력받을 필드와 필수 여부

-- ① 발행본. 필드는 해결 결과 · 담당자 · 코멘트 셋뿐이다(Maxi 2026-10-22). 순서 컬럼은 없다 —
--    화면은 늘 해결 결과 → 담당자 → 코멘트 순으로 그린다. 전환을 지우면 함께 사라진다(발행은 전량 교체).
CREATE TABLE workflow_transition_screen_fields (
    transition_id UUID    NOT NULL REFERENCES workflow_transitions (id) ON DELETE CASCADE,
    field         TEXT    NOT NULL CHECK (field IN ('resolution', 'assignee', 'comment')),
    required      BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (transition_id, field)
);

COMMENT ON TABLE workflow_transition_screen_fields
    IS '전환 화면 — 전환 때 대화상자로 받을 필드. 필수 여부의 정본이다 (FR-WF-01 D10)';
COMMENT ON COLUMN workflow_transition_screen_fields.required
    IS 'TRUE 면 전환 실행 때 비어 있으면 거절한다 (실행은 D11)';

-- ② 지금 해결 모달이 뜨는 자리 — DONE 범주로 들어가는 전환 — 에 「해결 결과 필수」를 심는다.
--    D11 이 하드코딩 모달을 걷어 내도 동작이 그대로이게 하려는 것이다. INITIAL 은 화면을 갖지 않는다.
INSERT INTO workflow_transition_screen_fields (transition_id, field, required)
SELECT t.id, 'resolution', TRUE
FROM workflow_transitions t
JOIN workflow_statuses ws ON ws.id = t.to_status_id
JOIN statuses s ON s.id = ws.status_id
WHERE s.category = 'DONE'
  AND t.kind <> 'INITIAL';

-- ③ 초안 JSONB 도 같은 규칙으로 채운다. 안 채우면 이 마이그레이션 전에 뜬 초안을 발행하는 순간
--    ② 가 심은 화면이 지워진다. 도착 상태의 범주는 초안 자신의 states 에서 읽는다.
--    발행 이력(workflow_publications)은 append-only 라 손대지 않는다.
--    전환이 빈 초안은 jsonb_agg 가 NULL 을 내고 jsonb_set 이 정의 전체를 NULL 로 만든다 — COALESCE 로 막는다.
UPDATE workflow_drafts d
SET definition = jsonb_set(
        d.definition,
        '{transitions}',
        COALESCE((SELECT jsonb_agg(
                    CASE
                        WHEN COALESCE(t ->> 'kind', 'NORMAL') <> 'INITIAL'
                            AND NOT t ? 'screen'
                            AND EXISTS (SELECT 1
                                        FROM jsonb_array_elements(d.definition -> 'states') st
                                        WHERE st ->> 'key' = t ->> 'to'
                                          AND st ->> 'category' = 'DONE')
                            THEN t || '{"screen": [{"field": "resolution", "required": true}]}'::jsonb
                        ELSE t
                        END
                    ORDER BY x.ord)
         FROM jsonb_array_elements(d.definition -> 'transitions') WITH ORDINALITY AS x(t, ord)), '[]'::jsonb))
WHERE jsonb_typeof(d.definition -> 'transitions') = 'array';

-- 해결 결과 필수 검증기를 전환 화면 「필수」로 옮긴다 (FR-WF-01 D11 · WF-01-1d) — 필수의 정본은 화면이다

-- Maxi 2026-10-22 — 「검증기를 걷는다」. D11 부터 서버가 화면의 필수를 강제하므로 `RequiredField(resolution)` 은
-- 같은 일을 두 번 한다. 옮긴 뒤 지운다. 사용자가 다른 필드에 건 `RequiredField` 와 INITIAL 전환의 검증기는
-- 그대로 둔다 — INITIAL 에는 화면이 없어(D10) 옮길 자리가 없다.

-- ① 발행본. 검증기가 걸린 비-INITIAL 전환의 화면에 「해결 결과 · 필수」를 넣는다. 이미 있으면 필수로 올린다.
INSERT INTO workflow_transition_screen_fields (transition_id, field, required)
SELECT DISTINCT v.transition_id, 'resolution', TRUE
FROM workflow_validators v
JOIN workflow_transitions t ON t.id = v.transition_id
WHERE v.type = 'RequiredField'
  AND v.config ->> 'field' = 'resolution'
  AND t.kind <> 'INITIAL'
ON CONFLICT (transition_id, field) DO UPDATE SET required = TRUE;

DELETE FROM workflow_validators v
USING workflow_transitions t
WHERE t.id = v.transition_id
  AND v.type = 'RequiredField'
  AND v.config ->> 'field' = 'resolution'
  AND t.kind <> 'INITIAL';

-- ② 초안 JSONB 도 같은 규칙으로 옮긴다. 안 옮기면 이 마이그레이션 전에 뜬 초안을 발행하는 순간 검증기가 돌아온다.
--    화면 배열은 필드 고정 순서(resolution 이 맨 앞)를 지킨다 — 해결 결과를 앞에 붙이고 기존 resolution 칸은 뺀다.
--    발행 이력(workflow_publications)은 append-only 라 손대지 않는다.
UPDATE workflow_drafts d
SET definition = jsonb_set(
        d.definition,
        '{transitions}',
        (SELECT jsonb_agg(
                    CASE
                        WHEN COALESCE(t ->> 'kind', 'NORMAL') <> 'INITIAL'
                            AND EXISTS (SELECT 1
                                        FROM jsonb_array_elements(COALESCE(t -> 'validators', '[]'::jsonb)) v
                                        WHERE v ->> 'type' = 'RequiredField'
                                          AND v -> 'config' ->> 'field' = 'resolution')
                            THEN t
                                     || jsonb_build_object(
                                         'validators',
                                         COALESCE((SELECT jsonb_agg(v ORDER BY vo)
                                                   FROM jsonb_array_elements(t -> 'validators') WITH ORDINALITY AS y(v, vo)
                                                   WHERE NOT (v ->> 'type' = 'RequiredField'
                                                       AND v -> 'config' ->> 'field' = 'resolution')), '[]'::jsonb),
                                         'screen',
                                         '[{"field": "resolution", "required": true}]'::jsonb
                                             || COALESCE((SELECT jsonb_agg(s ORDER BY so)
                                                          FROM jsonb_array_elements(COALESCE(t -> 'screen', '[]'::jsonb))
                                                              WITH ORDINALITY AS z(s, so)
                                                          WHERE s ->> 'field' <> 'resolution'), '[]'::jsonb))
                        ELSE t
                        END
                    ORDER BY x.ord)
         FROM jsonb_array_elements(d.definition -> 'transitions') WITH ORDINALITY AS x(t, ord)))
WHERE jsonb_typeof(d.definition -> 'transitions') = 'array'
  AND jsonb_array_length(d.definition -> 'transitions') > 0;

-- 자동화 룰의 조건 표현식(JSONLogic 부분집합 트리)을 저장하는 테이블 — 룰당 0..1 (FR-AT-03, ADR 조건 분기)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V303 사용 중 → V304.
--
-- AutomationRule(TCA 자동화 엔진)의 **조건 게이트**. FR-AT-02(액션)가 실행되기 전, 이 조건 트리가 참일
-- 때만 액션을 수행한다(if-else 분기). 조건은 하나의 트리(and/or/not/comparison)이므로 automation_actions
-- (N행 position)와 달리 **룰당 최대 한 행**이며, 이를 rule_id 를 PK 로 삼아 스키마 레벨에서 강제한다.
--
-- expression(JSONB)은 JSONLogic 부분집합 트리를 담는다(Condition.toJson()/fromJson() 와이어 포맷).
--   평가 로직은 DB 가 아니라 앱(ConditionEvaluator)이 수행한다 — DB 는 형식 없는 저장만 담당한다.
-- rule_id: 부모 룰 FK 겸 PK. ON DELETE CASCADE — 룰 삭제 시 소속 조건을 고아로 남기지 않고 함께 삭제한다.
--   (automation_conditions 는 룰 소유 하위 엔티티라 소프트 삭제 컬럼 없이 부모 CASCADE 로 정리한다.)

CREATE TABLE automation_conditions (
    rule_id    UUID PRIMARY KEY,                    -- 부모 룰 FK 겸 PK(룰당 0..1 행)
    expression JSONB NOT NULL,                      -- 조건 트리(JSONLogic 부분집합, Condition 와이어 포맷)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 생성 시각(DATA.md §4 — TIMESTAMPTZ 강제)
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 최종 수정 시각(upsert UPDATE 시 now() 갱신)
    -- 부모 룰 삭제 시 소속 조건 동반 삭제(고아 방지) — 룰 소유 하위 엔티티.
    CONSTRAINT fk_automation_conditions_rule
        FOREIGN KEY (rule_id) REFERENCES automation_rules (id) ON DELETE CASCADE
);

-- rule_id 가 PK 이므로 backing 인덱스가 자동 생성돼 rule_id FK 조회 인덱스를 겸한다 —
-- 별도 CREATE INDEX 없이 중복 인덱스를 피한다(V302 uq_automation_actions_rule_position 동형 판단).

COMMENT ON TABLE  automation_conditions            IS 'AutomationRule 조건 게이트 — 룰당 0..1 조건 트리(FR-AT-03)';
COMMENT ON COLUMN automation_conditions.rule_id    IS '부모 룰 FK 겸 PK(automation_rules.id) — ON DELETE CASCADE, 룰당 0..1';
COMMENT ON COLUMN automation_conditions.expression IS '조건 트리 JSONB(JSONLogic 부분집합 — Condition.toJson 와이어 포맷)';
COMMENT ON COLUMN automation_conditions.created_at IS '생성 시각(UTC)';
COMMENT ON COLUMN automation_conditions.updated_at IS '최종 수정 시각(UTC) — upsert UPDATE 시 갱신';

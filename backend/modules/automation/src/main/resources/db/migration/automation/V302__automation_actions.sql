-- automation BC 액션 저장 테이블 — automation_actions (FR-AT-02, ADR D1)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300/V301 사용 중 → V302.
--
-- AutomationRule(TCA 자동화 엔진)의 **액션 절반**. FR-AT-01(트리거)이 q_automation_execution 큐에 넣은 룰을
-- executor 가 소비해, 이 테이블에 순서(position)대로 저장된 4종 액션을 실행한다:
--   SET_FIELD(필드 설정) / ASSIGN(담당자 지정) / ADD_COMMENT(댓글 추가) / CALL_WEBHOOK(아웃바운드 웹훅).
--
-- action_type(4종)은 DB CHECK 제약으로 화이트리스트를 강제한다 — 앱 검증(ActionType enum)에 더해 DB 레벨에서도
-- 이중 방어한다(automation_rules.trigger_type 선례 동형). 데이터 무결성은 시스템의 마지막 방어선.
-- action_config(JSONB)는 액션별 형식만 담는다(cross-BC 존재 검증 안 함). 예:
--   SET_FIELD = {field, value} / ASSIGN = {assigneeId} / ADD_COMMENT = {body} / CALL_WEBHOOK = {url, ...}.
-- position: 룰 내 액션 실행 순서(0-base). UNIQUE(rule_id, position)로 같은 룰에서 순서 중복을 거부한다.
-- rule_id: 부모 룰 FK. ON DELETE CASCADE — 룰 삭제 시 소속 액션을 고아로 남기지 않고 함께 삭제한다.
--   (automation_actions 는 룰 소유 하위 엔티티라 소프트 삭제 컬럼 없이 부모 CASCADE 로 정리한다.)

-- gen_random_uuid() 보장 — V300 에서 이미 생성되나(같은 체인) IF NOT EXISTS 로 방어적 재선언(V301 동형).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE automation_actions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id       UUID NOT NULL,                       -- 부모 룰 FK(automation_rules.id)
    position      INTEGER NOT NULL,                    -- 룰 내 실행 순서(0-base)
    action_type   VARCHAR(20) NOT NULL,                -- 액션 종류(CHECK 4종 화이트리스트)
    action_config JSONB NOT NULL,                      -- 액션별 형식 설정(field/assignee/body/url 등)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 생성 시각(DATA.md §4 — TIMESTAMPTZ 강제)
    -- 부모 룰 삭제 시 소속 액션 동반 삭제(고아 방지) — 룰 소유 하위 엔티티.
    CONSTRAINT fk_automation_actions_rule
        FOREIGN KEY (rule_id) REFERENCES automation_rules (id) ON DELETE CASCADE,
    -- 같은 룰에서 실행 순서(position) 중복 거부. 이 UNIQUE 의 backing 인덱스가
    -- (rule_id, position) 조회 인덱스 겸 rule_id FK 인덱스(선두 컬럼) 역할을 한다 —
    -- 별도 CREATE INDEX 없이 중복 인덱스를 피한다.
    CONSTRAINT uq_automation_actions_rule_position UNIQUE (rule_id, position),
    -- action_type 4종 화이트리스트(ADR D1). 앱(ActionType enum)과 이중 방어.
    CONSTRAINT ck_automation_actions_action_type CHECK (
        action_type IN ('SET_FIELD', 'ASSIGN', 'ADD_COMMENT', 'CALL_WEBHOOK')
    )
);

-- 방금 생성한 빈 테이블이므로 CONCURRENTLY 를 쓰지 않는다 — CREATE INDEX CONCURRENTLY 는 Flyway 트랜잭션
-- 안에서 실행 불가하며, 0행 신규 테이블에는 락 회피 이점도 없다(V300 동형, DATA.md CONCURRENTLY 는 대형 기존 테이블 대상).

COMMENT ON TABLE  automation_actions               IS 'AutomationRule 액션 — 룰당 순서대로 실행하는 4종 액션(FR-AT-02)';
COMMENT ON COLUMN automation_actions.rule_id       IS '부모 룰 FK(automation_rules.id) — ON DELETE CASCADE';
COMMENT ON COLUMN automation_actions.position      IS '룰 내 실행 순서(0-base) — UNIQUE(rule_id, position)';
COMMENT ON COLUMN automation_actions.action_type   IS '액션 종류 — CHECK 4종(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK)';
COMMENT ON COLUMN automation_actions.action_config IS '액션별 형식 설정 JSONB(field/assignee/body/url 등)';
COMMENT ON COLUMN automation_actions.created_at    IS '생성 시각(UTC)';

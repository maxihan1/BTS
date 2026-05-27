-- 워크플로우 정의 5 테이블 (FR-WF-01 V001) — FSM 워크플로우 컨테이너 + 상태 + 전이 + 검증 + 후처리

-- ── workflows ─────────────────────────────────────────────────────────────────
-- FSM 워크플로우 Aggregate Root. key 로 식별하며 YamlSeedService 가 4종 표준 시드를 적재한다.
CREATE TABLE workflows (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    key         TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  workflows             IS 'FSM 워크플로우 Aggregate Root — 이슈 상태 전이 규칙 집합';
COMMENT ON COLUMN workflows.key         IS '워크플로우 식별 키 (예: software-default, bug-tracking)';
COMMENT ON COLUMN workflows.description IS '관리자용 설명 (null 허용)';

-- ── workflow_states ───────────────────────────────────────────────────────────
-- 워크플로우 안의 개별 상태 노드. category 로 TODO/IN_PROGRESS/DONE 분류한다.
CREATE TABLE workflow_states (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id   UUID        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    key           TEXT        NOT NULL,
    name          TEXT        NOT NULL,
    category      TEXT        NOT NULL CHECK (category IN ('TODO', 'IN_PROGRESS', 'DONE')),
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workflow_id, key)
);

COMMENT ON TABLE  workflow_states               IS '워크플로우 상태 노드 — FSM 의 각 노드 (TODO/IN_PROGRESS/DONE 분류)';
COMMENT ON COLUMN workflow_states.key           IS '상태 식별 키 (워크플로우 내 유일)';
COMMENT ON COLUMN workflow_states.category      IS '이슈 상태 대분류: TODO, IN_PROGRESS, DONE';
COMMENT ON COLUMN workflow_states.display_order IS 'UI 정렬 순서';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 인덱스 자동 생성 안 함)
CREATE INDEX idx_workflow_states_workflow
    ON workflow_states (workflow_id);

-- ── workflow_transitions ──────────────────────────────────────────────────────
-- 워크플로우 안의 상태 전이 엣지 (from_state → to_state). 동일 경로 중복 불가.
CREATE TABLE workflow_transitions (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id   UUID        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    from_state_id UUID        NOT NULL REFERENCES workflow_states (id) ON DELETE CASCADE,
    to_state_id   UUID        NOT NULL REFERENCES workflow_states (id) ON DELETE CASCADE,
    name          TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workflow_id, from_state_id, to_state_id)
);

COMMENT ON TABLE  workflow_transitions               IS '워크플로우 전이 엣지 — from 상태에서 to 상태로의 허용 경로';
COMMENT ON COLUMN workflow_transitions.name          IS '전이 레이블 (예: 작업 시작, 완료, 반려)';
COMMENT ON COLUMN workflow_transitions.from_state_id IS '전이 출발 상태 FK';
COMMENT ON COLUMN workflow_transitions.to_state_id   IS '전이 도착 상태 FK';

-- FK 인덱스 3개 (workflow_id, from_state_id, to_state_id — DATA.md §7)
CREATE INDEX idx_workflow_transitions_workflow
    ON workflow_transitions (workflow_id);

CREATE INDEX idx_workflow_transitions_from
    ON workflow_transitions (from_state_id);

-- to_state_id 인덱스 추가 — CONCERN-6 해소 (DATA.md §7 FK 인덱스 룰)
CREATE INDEX idx_workflow_transitions_to
    ON workflow_transitions (to_state_id);

-- ── workflow_validators ───────────────────────────────────────────────────────
-- 전이 전 게이트 조건. type + config JSONB 으로 구현체(RequiredField/Permission/CustomExpression)를 구분한다.
CREATE TABLE workflow_validators (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transition_id UUID        NOT NULL REFERENCES workflow_transitions (id) ON DELETE CASCADE,
    type          TEXT        NOT NULL,
    config        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  workflow_validators               IS '전이 전 검증 게이트 — RequiredField/Permission/CustomExpression 타입 구분';
COMMENT ON COLUMN workflow_validators.type          IS '구현 타입 식별자 (예: RequiredField, Permission, CustomExpression)';
COMMENT ON COLUMN workflow_validators.config        IS '타입별 파라미터 JSONB (예: {\"field\":\"resolution\"})';
COMMENT ON COLUMN workflow_validators.display_order IS '다수 Validator 평가 순서';

-- FK 인덱스 (DATA.md §7)
CREATE INDEX idx_workflow_validators_transition
    ON workflow_validators (transition_id);

-- ── workflow_post_actions ─────────────────────────────────────────────────────
-- 전이 후 자동 처리. 실행은 호출자(issue-tracking) BC 책임 (GAP-2 결정).
CREATE TABLE workflow_post_actions (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transition_id UUID        NOT NULL REFERENCES workflow_transitions (id) ON DELETE CASCADE,
    type          TEXT        NOT NULL,
    config        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  workflow_post_actions               IS '전이 후 자동 처리 — SetField/AddWatcher/Notify/CallWebhook/RunAutomation 타입';
COMMENT ON COLUMN workflow_post_actions.type          IS '구현 타입 식별자 (예: SetField, AddWatcher, Notify, CallWebhook, RunAutomation)';
COMMENT ON COLUMN workflow_post_actions.config        IS '타입별 파라미터 JSONB (예: {\"field\":\"assignee\",\"value\":\"${actor}\"})';
COMMENT ON COLUMN workflow_post_actions.display_order IS '다수 PostAction 실행 순서';

-- FK 인덱스 (DATA.md §7)
CREATE INDEX idx_workflow_post_actions_transition
    ON workflow_post_actions (transition_id);

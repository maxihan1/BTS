-- jOOQ 코드 생성용 초기화 SQL (project-workflow BC) — V200 · V203 · V205 구조 미러 (시드 제외, codegen은 구조만 필요)
--
-- workflows / workflow_states / workflow_transitions / workflow_validators / workflow_post_actions DDL 은
-- V200__init_workflow.sql 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성).
-- workflows 의 version/origin/deleted_at/is_locked 는 V205__workflows_add_version_origin.sql 과 동일하게 유지한다.
-- statuses / workflow_statuses DDL 은 V203__add_global_status_catalog.sql 과 동일하게 유지한다.
--
-- V201(workflow_schemes) · V202 는 **의도적으로 미러하지 않는다.** 종전에도 코드젠 밖이었고
-- 소비처(SchemeIssueTypeMappingRepository)가 그 전제 위에 원시 SQL 로 쓰여 있다. 여기에 넣으면
-- 생성물이 갑자기 늘어 이 PR 범위를 벗어난다.
--
-- ★ 이 파일과 마이그레이션이 갈라지지 않는지는 `CodegenMirrorParityTest` 가 실제 PostgreSQL 두 곳에
--   적용해 information_schema 로 대조한다. 손으로 지키는 사본이 조용히 썩는 것을 그 판별식이 막는다.

-- ── workflows ─────────────────────────────────────────────────────────────────
CREATE TABLE workflows (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    key         TEXT        NOT NULL,  -- V206. 컬럼 UNIQUE → 부분 유니크 인덱스 uq_workflows_key
    name        TEXT        NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- V205
    version     BIGINT      NOT NULL DEFAULT 0,
    origin      TEXT        NOT NULL DEFAULT 'CUSTOM',
    deleted_at  TIMESTAMPTZ,
    is_locked   BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_workflows_origin CHECK (origin IN ('SEED', 'CUSTOM'))
);

CREATE UNIQUE INDEX uq_workflows_key ON workflows (key) WHERE deleted_at IS NULL;

-- ── workflow_states ───────────────────────────────────────────────────────────
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

CREATE INDEX idx_workflow_states_workflow ON workflow_states (workflow_id);

-- ── workflow_transitions ──────────────────────────────────────────────────────
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

CREATE INDEX idx_workflow_transitions_workflow ON workflow_transitions (workflow_id);
CREATE INDEX idx_workflow_transitions_from ON workflow_transitions (from_state_id);
CREATE INDEX idx_workflow_transitions_to ON workflow_transitions (to_state_id);

-- ── workflow_validators ───────────────────────────────────────────────────────
CREATE TABLE workflow_validators (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transition_id UUID        NOT NULL REFERENCES workflow_transitions (id) ON DELETE CASCADE,
    type          TEXT        NOT NULL,
    config        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_workflow_validators_transition ON workflow_validators (transition_id);

-- ── workflow_post_actions ─────────────────────────────────────────────────────
CREATE TABLE workflow_post_actions (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transition_id UUID        NOT NULL REFERENCES workflow_transitions (id) ON DELETE CASCADE,
    type          TEXT        NOT NULL,
    config        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_workflow_post_actions_transition ON workflow_post_actions (transition_id);

-- ── statuses (V203) ───────────────────────────────────────────────────────────
CREATE TABLE statuses (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key         VARCHAR(50)  NOT NULL,  -- V206. 컬럼 UNIQUE → 부분 유니크 인덱스 uq_statuses_key
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    category    TEXT         NOT NULL CHECK (category IN ('TODO', 'IN_PROGRESS', 'DONE')),
    is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_statuses_lower_name ON statuses (lower(name)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_statuses_key ON statuses (key) WHERE deleted_at IS NULL;

-- ── workflow_statuses (V203) ──────────────────────────────────────────────────
CREATE TABLE workflow_statuses (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id   UUID        NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    status_id     UUID        NOT NULL REFERENCES statuses (id)  ON DELETE RESTRICT,
    display_order INTEGER     NOT NULL DEFAULT 0,
    layout_x      REAL,
    layout_y      REAL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workflow_id, status_id)
);

CREATE INDEX idx_workflow_statuses_workflow ON workflow_statuses (workflow_id);
CREATE INDEX idx_workflow_statuses_status ON workflow_statuses (status_id);

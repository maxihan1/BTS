-- jOOQ 코드 생성용 초기화 SQL (project-workflow BC) — V200 · V203 · V205 · V207 · V208 구조 미러 (시드 제외, codegen은 구조만 필요)
--
-- workflows / workflow_states / workflow_transitions / workflow_validators / workflow_post_actions DDL 은
-- V200__init_workflow.sql 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성).
-- workflows 의 version/origin/deleted_at/is_locked 는 V205__workflows_add_version_origin.sql 과 동일하게 유지한다.
-- statuses / workflow_statuses DDL 은 V203__add_global_status_catalog.sql 과 동일하게 유지한다.
-- workflow_transitions 의 kind/from_status_id/to_status_id/display_order 는
-- V207__transitions_multi_and_global.sql 과 동일하게 유지한다 (파일 끝의 V207 블록).
--
-- V201(workflow_schemes) · V202 는 **의도적으로 미러하지 않는다.** 종전에도 코드젠 밖이었고
-- 소비처(SchemeIssueTypeMappingRepository)가 그 전제 위에 원시 SQL 로 쓰여 있다. 여기에 넣으면
-- 생성물이 갑자기 늘어 이 PR 범위를 벗어난다.
--
-- workflow_drafts / workflow_publications DDL 은 V208__workflow_drafts_and_publications.sql 과
-- 동일하게 유지한다. 단 그 파일의 append-only 트리거는 미러하지 않는다 (파일 끝 사유 참고).
--
-- ★ **이 사본을 지키는 판별식은 현재 없다.** 종전 주석은 `CodegenMirrorParityTest` 가 실 PostgreSQL
--   두 곳에 적용해 information_schema 로 대조한다고 적었으나, 그 테스트는 저장소에 실재하지 않는다
--   (2026-08-26 전 모듈 grep — 이름이 나오는 곳은 주석 5건뿐). 미러 누락은 지금 아무도 잡지 않으므로
--   마이그레이션을 추가하는 사람이 **같은 커밋에서** 손으로 지켜야 한다. 판별식 신설은 부채로 등재됐다.

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
    -- V207. NOT NULL 해제 — GLOBAL·INITIAL 전환은 구 FK 를 채우지 않는다 (컬럼 자체는 살려 둔다)
    from_state_id UUID        REFERENCES workflow_states (id) ON DELETE CASCADE,
    to_state_id   UUID        REFERENCES workflow_states (id) ON DELETE CASCADE,
    name          TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
    -- V207. UNIQUE (workflow_id, from_state_id, to_state_id) 해제 — 같은 상태쌍에 전환을 여럿 둔다
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

-- ── workflow_transitions 확장 (V207) ──────────────────────────────────────────
-- workflow_statuses 뒤에 둔다 — 참조 대상 테이블이 먼저 있어야 한다.
-- 컬럼 추가 순서를 V207 과 똑같이 맞춘다. 순서가 갈리면 jOOQ 생성 필드 순서가 실 DB 와 어긋난다.
ALTER TABLE workflow_transitions
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'NORMAL';

ALTER TABLE workflow_transitions
    ADD CONSTRAINT ck_workflow_transitions_kind CHECK (kind IN ('NORMAL', 'GLOBAL', 'INITIAL'));

ALTER TABLE workflow_transitions
    ADD COLUMN from_status_id UUID    NULL REFERENCES workflow_statuses (id) ON DELETE CASCADE,
    ADD COLUMN to_status_id   UUID    NULL REFERENCES workflow_statuses (id) ON DELETE CASCADE,
    ADD COLUMN display_order  INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_workflow_transitions_from_status ON workflow_transitions (from_status_id);
CREATE INDEX idx_workflow_transitions_to_status ON workflow_transitions (to_status_id);

-- V207 ⑦·⑪. to_status_id 의 NOT NULL 승격과 ck_transition_kind_from CHECK 는 **3단계로 이연**했다.
-- 2단계인 지금은 구·신 컬럼이 공존하는 구간이라 구 컬럼만 채우는 INSERT 가 아직 정상 경로다.
-- 미러가 마이그레이션보다 엄격하면 코드젠 DB 에서만 통과하는 쿼리가 생긴다 — 반드시 같이 비워 둔다.

CREATE UNIQUE INDEX uq_workflow_transitions_initial
    ON workflow_transitions (workflow_id)
 WHERE kind = 'INITIAL';

-- ── workflow_drafts (V208) ────────────────────────────────────────────────────
CREATE TABLE workflow_drafts (
    workflow_id  UUID        PRIMARY KEY
                             CONSTRAINT fk_workflow_drafts_workflow
                             REFERENCES workflows (id) ON DELETE CASCADE,
    definition   JSONB       NOT NULL,
    base_version BIGINT      NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by   UUID
);

-- ── workflow_publications (V208) ──────────────────────────────────────────────
CREATE TABLE workflow_publications (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id  UUID        NOT NULL
                             CONSTRAINT fk_workflow_publications_workflow
                             REFERENCES workflows (id) ON DELETE CASCADE,
    version_no   INTEGER     NOT NULL,
    definition   JSONB       NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_by UUID,
    CONSTRAINT uq_workflow_publications_version UNIQUE (workflow_id, version_no),
    CONSTRAINT ck_workflow_publications_version_positive CHECK (version_no > 0)
);

CREATE INDEX idx_workflow_publications_published_at
    ON workflow_publications (workflow_id, published_at DESC);

-- V208 ③. append-only 트리거(`trg_workflow_publications_append_only`)와 그 함수는
-- **의도적으로 미러하지 않는다.** jOOQ codegen 은 information_schema 의 테이블·컬럼·제약만 읽어
-- 상수를 만들고 트리거는 생성물에 나타나지 않는다. 미러가 마이그레이션보다 **느슨한** 방향이라
-- 코드젠 DB 에서만 통과하는 쿼리가 생기지 않는다(엄격한 쪽이 위험하다 — 위 V207 주석 참고).

-- project-workflow V004 — workflow_schemes + project_workflow_scheme_assignments + workflow_scheme_issue_type_mappings + pgmq queue + 4 표준 seed

-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-- 배경 (ADR project-scheme-mapping-jira-align 참조)
--
-- Jira WorkflowScheme 모델 high-level align:
--   Project N — 1 WorkflowScheme     → 별도 매핑 테이블 project_workflow_scheme_assignments
--   WorkflowScheme 1 — N Mapping     → workflow_scheme_issue_type_mappings
--   Mapping: (scheme_id, issue_type_id nullable) → workflow_id
--
-- nodeassociation(다목적 통합 테이블) 패턴은 BTS 1K 사용자 규모에 over-engineered 라 미도입.
-- high-level concept(별도 매핑 + NULL=default 패턴) 만 align.
-- ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

-- pgmq 확장 보장 — project-workflow 모듈 별도 Flyway namespace 로 재확인
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- ── 1. workflow_schemes (§5.1.1) ───────────────────────────────────────────────
-- WorkflowScheme Aggregate Root.
-- is_default = true 인 4개 표준 스킴은 key/name/description/is_default 변경 불가 (FR-WF-02-07 application layer 검증).
-- mapping 은 admin 이 변경 가능 (D11 결정).
-- deleted_at: 소프트 삭제 컬럼 (DATA.md §3) — NULL=활성, NOT NULL=삭제됨.
CREATE TABLE workflow_schemes (
    id          BIGSERIAL    PRIMARY KEY,
    key         VARCHAR(30)  NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    -- is_default: true = 시스템 표준 스킴. key/name/description/is_default 변경 불가 (FR-WF-02-07).
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

COMMENT ON TABLE  workflow_schemes            IS 'WorkflowScheme Aggregate Root — 이슈 타입별 워크플로우 매핑 묶음';
COMMENT ON COLUMN workflow_schemes.key        IS '스킴 식별 키 (URL-safe, ^[a-z][a-z0-9-]{1,29}$ — WorkflowSchemeKey VO 패턴)';
COMMENT ON COLUMN workflow_schemes.is_default IS 'true = 시스템 표준 스킴 (4개). key/name/description/is_default 변경/삭제 불가.';
COMMENT ON COLUMN workflow_schemes.deleted_at IS 'NULL=활성, NOT NULL=소프트 삭제 (DATA.md §3)';

-- 활성 스킴 key 조회 최적화 (deleted_at IS NULL 기준)
CREATE INDEX ix_workflow_schemes_key_active
    ON workflow_schemes (key)
    WHERE deleted_at IS NULL;

-- ── 2. project_workflow_scheme_assignments (§5.1.2) ────────────────────────────
-- Project ↔ WorkflowScheme N:1 매핑 (한 프로젝트 = 한 스킴).
-- Jira align: 별도 매핑 테이블 — projects 컬럼 직접 FK 아님 (D9 옵션 C, ADR project-scheme-mapping-jira-align).
--
-- project_id ↔ projects.id cross-BC FK 주의.
-- projects 테이블은 다른 모듈 또는 미생성 상태일 수 있어 FK 제약 없이 BIGINT 만 명시.
-- SDD §05.3 정정 commit T35 단계 (D9 옵션 C 채택 후 별도 docs cleanup PR).
CREATE TABLE project_workflow_scheme_assignments (
    project_id         BIGINT      PRIMARY KEY,
    workflow_scheme_id BIGINT      NOT NULL REFERENCES workflow_schemes(id) ON DELETE RESTRICT,
    assigned_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- assigned_by: 적용한 admin 사용자 UUID (감사 로그 — FR-HS 후속 PR 연계)
    assigned_by        UUID        NOT NULL
);

COMMENT ON TABLE  project_workflow_scheme_assignments                IS 'Project ↔ WorkflowScheme 1:N 매핑 (Jira align — 별도 매핑 테이블). 한 프로젝트 = 한 스킴.';
COMMENT ON COLUMN project_workflow_scheme_assignments.project_id     IS 'projects.id cross-BC 참조 (FK 제약 없음 — D9/SDD §05.3 정정 대상). BIGINT ONLY.';
COMMENT ON COLUMN project_workflow_scheme_assignments.assigned_by    IS '스킴 적용 admin UUID (감사 추적 — FR-HS 후속 PR)';

-- FK 인덱스 — PostgreSQL FK 자동 인덱스 생성 안 함 (DATA.md §7)
CREATE INDEX ix_pwsa_scheme
    ON project_workflow_scheme_assignments (workflow_scheme_id);

-- ── 3. workflow_scheme_issue_type_mappings (§5.1.3) ────────────────────────────
-- 스킴 안의 이슈 타입 → 워크플로우 매핑 규칙.
-- issue_type_id NULL = default mapping (Jira workflowschemeentity 의 issuetype=NULL 패턴 align).
-- partial UNIQUE INDEX ix_scheme_default_mapping 로 스킴당 default mapping 1건 강제.
--
-- issue_type_id ↔ issue_types.id cross-BC FK 허용 (D13 — 두 BC 모두 본 PR scope, 같은 DB 인스턴스).
--   V003 (issue-tracking) 먼저 실행 → V004 (project-workflow) 순서 보장.
--
-- workflow_id ↔ workflows.id 는 같은 BC (project-workflow) FK.
--   workflows.id 타입 = UUID (V001 PRIMARY KEY DEFAULT gen_random_uuid()).
CREATE TABLE workflow_scheme_issue_type_mappings (
    id             BIGSERIAL   PRIMARY KEY,
    scheme_id      BIGINT      NOT NULL REFERENCES workflow_schemes(id) ON DELETE CASCADE,
    -- issue_type_id: NULL = 이 스킴의 default workflow (매핑되지 않은 모든 타입에 적용).
    issue_type_id  BIGINT      REFERENCES issue_types(id) ON DELETE RESTRICT,
    -- workflow_id: workflows.id 는 UUID 타입 (V001 참조).
    workflow_id    UUID        NOT NULL REFERENCES workflows(id) ON DELETE RESTRICT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_scheme_issue_type UNIQUE (scheme_id, issue_type_id)
);

COMMENT ON TABLE  workflow_scheme_issue_type_mappings               IS 'WorkflowScheme 안의 이슈 타입 → 워크플로우 매핑. NULL issue_type_id = default workflow (Jira workflowschemeentity 패턴).';
COMMENT ON COLUMN workflow_scheme_issue_type_mappings.issue_type_id IS 'NULL = default mapping (매핑되지 않은 모든 타입). NOT NULL = 특정 타입 전용. cross-BC FK (D13 허용).';
COMMENT ON COLUMN workflow_scheme_issue_type_mappings.workflow_id   IS 'workflows.id (UUID 타입 — V001 참조). 같은 BC FK (ON DELETE RESTRICT).';

-- FK 인덱스 (DATA.md §7)
CREATE INDEX ix_wsitm_scheme
    ON workflow_scheme_issue_type_mappings (scheme_id);

CREATE INDEX ix_wsitm_issue_type
    ON workflow_scheme_issue_type_mappings (issue_type_id);

CREATE INDEX ix_wsitm_workflow
    ON workflow_scheme_issue_type_mappings (workflow_id);

-- partial UNIQUE INDEX — default mapping (issue_type_id IS NULL) 스킴 별 1개 강제
-- UNIQUE constraint(uq_scheme_issue_type) 는 NULL ≠ NULL 로 동작해 중복 방지 안 됨.
-- partial index 로 (scheme_id) WHERE issue_type_id IS NULL UNIQUE 보완.
CREATE UNIQUE INDEX ix_scheme_default_mapping
    ON workflow_scheme_issue_type_mappings (scheme_id)
    WHERE issue_type_id IS NULL;

-- ── 4. pgmq 이벤트 큐 (§5.1.4) ────────────────────────────────────────────────
-- WorkflowSchemeAssignedEvent / WorkflowSchemeUpdatedEvent / WorkflowSchemeDeletedEvent 발행.
-- consumer: 후속 BC 작업 (notification / audit-log / issue-tracking) — 본 마이그레이션 범위 외.
SELECT pgmq.create('q_workflow_scheme_events');

-- ── 5. 4 표준 스킴 INSERT seed (§5.1.5) ────────────────────────────────────────
-- is_default = true: 시스템 표준 스킴 — key/name/description/is_default 변경/삭제 불가 (FR-WF-02-07).
-- mapping 은 admin 이 변경 가능 (D11 결정 — mapping 자유, 4 field 만 lock).
INSERT INTO workflow_schemes (key, name, description, is_default) VALUES
    ('software-scheme',     'Software',     '소프트웨어 개발 표준 스킴 (기본)', TRUE),
    ('bug-tracking-scheme', 'Bug Tracking', '버그 추적 전용 스킴',              TRUE),
    ('simple-scheme',       'Simple',       '단순 워크플로우 (To Do → Done)',   TRUE),
    ('kanban-scheme',       'Kanban',       '칸반 보드 스킴',                   TRUE);

-- ── 6. 4 표준 스킴 default mapping seed (§5.1.6) ───────────────────────────────
-- 각 표준 스킴에 default mapping (issue_type_id IS NULL) 1건씩 INSERT.
-- workflows key 매핑 — V001 YAML 파일명과 동일:
--   software-scheme     → software-default  (workflows/software-default.yaml)
--   bug-tracking-scheme → bug-tracking      (workflows/bug-tracking.yaml)
--   simple-scheme       → simple            (workflows/simple.yaml)
--   kanban-scheme       → kanban-basic      (workflows/kanban-basic.yaml)
--
-- 실행 순서 의존성: workflows 테이블은 YamlSeedService(ApplicationReadyEvent) 가 채운다.
-- Flyway migrate(V004) 는 Spring Boot 기동 전에 실행되므로 workflows 가 비어 있으면 0건 삽입.
-- 이 경우 default mapping 은 후속 ApplicationRunner 에서 보완 (Wave-2 범위 — EC-2 참조).
--
-- workflows.deleted_at 없음 (V001 — soft-delete 미도입). WHERE 조건 없이 전체 JOIN.
INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
SELECT s.id, NULL, w.id
FROM workflow_schemes s
JOIN workflows w ON w.key = CASE s.key
    WHEN 'software-scheme'     THEN 'software-default'
    WHEN 'bug-tracking-scheme' THEN 'bug-tracking'
    WHEN 'simple-scheme'       THEN 'simple'
    WHEN 'kanban-scheme'       THEN 'kanban-basic'
END;

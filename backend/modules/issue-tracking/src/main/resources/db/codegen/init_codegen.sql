-- jOOQ codegen 전용 통합 init SQL — V001 + V002 순서대로 적용.
-- Flyway migration 파일(db/migration/)과 별개로 관리되며, generateJooq 태스크의 TC_INITSCRIPT 로 사용됨.
-- TC_INITSCRIPT 는 단일 파일만 지원하므로 두 마이그레이션을 여기에 통합한다.
-- 이미지: quay.io/tembo/pg16-pgmq:latest (ADR 2026-05-22-pgmq-postgres-image 채택 결정).
-- pgmq 스키마는 jOOQ codegen 대상에서 제외 — 호출은 raw SQL (dsl.execute("SELECT pgmq.send(...)")).

-- ═══════════════════════════════════════════════════════════════════════════
-- V001: issue-tracking 초기 스키마 (projects, issues, issue_key_redirects)
-- 원본: db/migration/issue-tracking/V001__issues_initial.sql
-- ═══════════════════════════════════════════════════════════════════════════

-- 1. pgcrypto extension (gen_random_uuid 용)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 2. projects 테이블
CREATE TABLE projects (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key           VARCHAR(10)  NOT NULL UNIQUE CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name          VARCHAR(255) NOT NULL,
    key_sequence  BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  projects              IS '이슈 컨테이너. key 는 영구 보존 (DATA.md §1.1).';
COMMENT ON COLUMN projects.key          IS '프로젝트 접두사 — 대문자로 시작, 대문자+숫자 2~10자 (예: BTS, ATLAS1). 이슈 키 생성의 기반.';
COMMENT ON COLUMN projects.key_sequence IS '다음 이슈에 부여할 일련번호. 이슈 생성 시 ApplicationService 가 SELECT FOR UPDATE 후 증가.';
COMMENT ON COLUMN projects.deleted_at   IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3).';

-- 3. issues 테이블
CREATE TABLE issues (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key                VARCHAR(20)  NOT NULL UNIQUE CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$'),
    project_id         UUID         NOT NULL REFERENCES projects(id),
    summary            VARCHAR(255) NOT NULL,
    reporter_id        UUID         NOT NULL,
    current_state_key  VARCHAR(50)  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  issues                     IS '이슈 단건. key 는 영구 보존 — 소프트 삭제 후에도 row 잔존 (DATA.md §1.1).';
COMMENT ON COLUMN issues.key                 IS '이슈 전역 식별자 (예: BTS-1). UNIQUE 제약으로 삭제 후 재발급 불가.';
COMMENT ON COLUMN issues.reporter_id         IS 'identity-access BC users.id 대응. BC 격리로 FK 미적용 — ApplicationService 가 존재 guard.';
COMMENT ON COLUMN issues.current_state_key   IS 'project-workflow BC workflow_states.key 대응. BC 격리로 FK 미적용.';
COMMENT ON COLUMN issues.version             IS '낙관적 잠금 카운터. 동시 수정 충돌 감지용.';
COMMENT ON COLUMN issues.deleted_at          IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3). key 는 삭제 후에도 UNIQUE 제약 유지.';

CREATE INDEX idx_issues_project_id ON issues(project_id);
CREATE INDEX idx_issues_project_id_deleted_at ON issues(project_id, deleted_at) WHERE deleted_at IS NULL;

-- 4. issue_key_redirects 테이블
CREATE TABLE issue_key_redirects (
    old_key        VARCHAR(20)  PRIMARY KEY,
    new_key        VARCHAR(20)  NOT NULL,
    redirected_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  issue_key_redirects             IS '이슈 이동 시 옛 키 → 새 키 영구 매핑. FR-MV-01 활성화 시 사용. append-only (DATA.md §1.1).';
COMMENT ON COLUMN issue_key_redirects.old_key     IS '이전 이슈 키 (PK). 한 번 기록된 값 수정/삭제 불가.';
COMMENT ON COLUMN issue_key_redirects.new_key     IS '이동 후 현재 이슈 키. 체인 이동 시 최종 키로 업데이트하지 않음 — 조회 시 체인 순회.';
COMMENT ON COLUMN issue_key_redirects.redirected_at IS '리다이렉트 기록 시각.';

CREATE INDEX idx_issue_key_redirects_new_key ON issue_key_redirects(new_key);

-- ═══════════════════════════════════════════════════════════════════════════
-- V002: pgmq 확장 + q_issue_events 큐 생성
-- 원본: db/migration/issue-tracking/V002__pgmq_queue_issue_events.sql
-- 이미지: quay.io/tembo/pg16-pgmq:latest — pgmq 사전 설치됨 (ADR 2026-05-22-pgmq-postgres-image).
-- ═══════════════════════════════════════════════════════════════════════════

-- pgmq extension 보장
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_issue_events
SELECT pgmq.create('q_issue_events');

COMMENT ON SCHEMA pgmq IS 'PostgreSQL 기반 메시지 큐 (Kafka 대체). DATA.md §7.2.';

-- ═══════════════════════════════════════════════════════════════════════════
-- V003: issue_types 테이블 + 5 표준 seed (FR-WF-02 cross-BC 사전 도입)
-- 원본: db/migration/V003__issue_types.sql
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE issue_types (
    id           BIGSERIAL    PRIMARY KEY,
    key          VARCHAR(30)  NOT NULL UNIQUE,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    icon_name    VARCHAR(50),
    is_standard  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMPTZ
);

CREATE INDEX ix_issue_types_key_active ON issue_types (key) WHERE deleted_at IS NULL;

INSERT INTO issue_types (key, name, description, icon_name, is_standard) VALUES
    ('epic',    'Epic',    '큰 작업 단위 (자식 이슈 보유)', 'epic',    true),
    ('story',   'Story',   '사용자 가치 단위',              'story',   true),
    ('task',    'Task',    '일반 작업',                     'task',    true),
    ('subtask', 'Subtask', '하위 작업',       'subtask', true),
    ('bug',     'Bug',     '결함',            'bug',     true);

-- ═══════════════════════════════════════════════════════════════════════════
-- V004: issues.current_state_key 소문자 정규화 (데이터 변경만 — 스키마 DDL 없음)
-- 원본: db/migration/issue-tracking/V004__lowercase_current_state_key.sql
-- jOOQ codegen 에 영향 없음. 완전성을 위해 주석으로만 포함.
-- ═══════════════════════════════════════════════════════════════════════════

-- (스키마 변경 없음 — codegen init 에 DDL 추가 불필요)

-- ═══════════════════════════════════════════════════════════════════════════
-- V005: issue_types.hierarchy_level 컬럼 + issues.type_id FK + 부분 unique 인덱스 교체
-- 원본: db/migration/issue-tracking/V005__issue_type_hierarchy_and_issue_type_fk.sql
-- ═══════════════════════════════════════════════════════════════════════════

-- 1. issue_types.hierarchy_level 컬럼 추가 (jOOQ: IssueTypes.HIERARCHY_LEVEL 생성 대상)
ALTER TABLE issue_types
    ADD COLUMN hierarchy_level INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN issue_types.hierarchy_level IS
    '이슈 유형 계층 깊이. epic=1(최상위), task/story/bug=0(기본), subtask=-1(하위 작업).';

-- epic: 하위 이슈를 묶는 최상위 컨테이너 → level 1
UPDATE issue_types SET hierarchy_level = 1  WHERE key = 'epic';
-- subtask: 다른 이슈의 하위 작업 → level -1
UPDATE issue_types SET hierarchy_level = -1 WHERE key = 'subtask';

-- 2. (B1) key UNIQUE 제약 교체 — 전체 unique → 부분 unique (활성 row 만)
ALTER TABLE issue_types
    DROP CONSTRAINT IF EXISTS issue_types_key_key;

CREATE UNIQUE INDEX ux_issue_types_key_active
    ON issue_types (key)
    WHERE deleted_at IS NULL;

DROP INDEX IF EXISTS ix_issue_types_key_active;

-- 3. issues.type_id 컬럼 추가 (jOOQ: Issues.TYPE_ID 생성 대상)
ALTER TABLE issues
    ADD COLUMN type_id BIGINT;

COMMENT ON COLUMN issues.type_id IS
    'issue_types.id FK. 이슈 유형 식별자. NOT NULL — 이슈는 반드시 유형을 가진다.';

-- 기존 row backfill — task 타입으로 채운다 (V005 이전 이슈는 기본 task 유형으로 간주)
UPDATE issues
   SET type_id = (
       SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1
   )
 WHERE type_id IS NULL;

-- backfill 완료 후 NOT NULL 제약 적용
ALTER TABLE issues
    ALTER COLUMN type_id SET NOT NULL;

-- FK 제약
ALTER TABLE issues
    ADD CONSTRAINT fk_issues_type_id
        FOREIGN KEY (type_id)
        REFERENCES issue_types (id);

-- FK 인덱스 (PostgreSQL 은 FK 에 인덱스 자동 생성 안 함)
CREATE INDEX ix_issues_type_id
    ON issues (type_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- V006: issues 5컬럼 추가 (description, priority, labels, environment, impact) + GIN 인덱스
-- 원본: db/migration/issue-tracking/V006__issue_body_priority_labels.sql
-- ═══════════════════════════════════════════════════════════════════════════

-- 5컬럼 추가 (jOOQ: Issues.DESCRIPTION/PRIORITY/LABELS/ENVIRONMENT/IMPACT 생성 대상)
ALTER TABLE issues
    ADD COLUMN description  TEXT,
    ADD COLUMN priority     SMALLINT NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
    ADD COLUMN labels       TEXT[]   NOT NULL DEFAULT '{}',
    ADD COLUMN environment  TEXT,
    ADD COLUMN impact       SMALLINT CHECK (impact BETWEEN 1 AND 3);

-- GIN 인덱스 — labels 배열 원소 검색용
CREATE INDEX ix_issues_labels_gin ON issues USING GIN (labels);

-- ═══════════════════════════════════════════════════════════════════════════
-- V007: issues.assignee_id UUID NULL 컬럼 추가
-- 원본: db/migration/issue-tracking/V007__issue_assignee.sql
-- ═══════════════════════════════════════════════════════════════════════════

-- assignee_id 추가 (jOOQ: Issues.ASSIGNEE_ID 생성 대상)
ALTER TABLE issues ADD COLUMN assignee_id UUID NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- V008: bulk_operations / bulk_operation_items 테이블 (FR-IS-05 일괄작업)
-- 원본: db/migration/issue-tracking/V008__bulk_operations.sql
-- pgmq 큐 생성(pgmq.create)은 jOOQ codegen 대상 외 — V002 선례 동일.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE bulk_operations (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_type   TEXT         NOT NULL
                         CONSTRAINT chk_bulk_operations_operation_type
                             CHECK (operation_type IN ('BULK_EDIT', 'BULK_TRANSITION')),
    status           TEXT         NOT NULL
                         CONSTRAINT chk_bulk_operations_status
                             CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    actor_id         UUID         NOT NULL,
    payload          JSONB        NOT NULL,
    total_count      INT          NOT NULL
                         CONSTRAINT chk_bulk_operations_total_count_gte0
                             CHECK (total_count >= 0),
    processed_count  INT          NOT NULL DEFAULT 0
                         CONSTRAINT chk_bulk_operations_processed_count_gte0
                             CHECK (processed_count >= 0),
    succeeded_count  INT          NOT NULL DEFAULT 0
                         CONSTRAINT chk_bulk_operations_succeeded_count_gte0
                             CHECK (succeeded_count >= 0),
    failed_count     INT          NOT NULL DEFAULT 0
                         CONSTRAINT chk_bulk_operations_failed_count_gte0
                             CHECK (failed_count >= 0),
    created_at       TIMESTAMPTZ  NOT NULL,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ
);

CREATE INDEX idx_bulk_operations_actor_id ON bulk_operations (actor_id);
CREATE INDEX idx_bulk_operations_status ON bulk_operations (status);

CREATE TABLE bulk_operation_items (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bulk_operation_id  UUID         NOT NULL REFERENCES bulk_operations (id),
    issue_key          TEXT         NOT NULL
                           CONSTRAINT chk_bulk_operation_items_issue_key
                               CHECK (issue_key ~ '^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$'),
    status             TEXT         NOT NULL
                           CONSTRAINT chk_bulk_operation_items_status
                               CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure_reason     TEXT,
    processed_at       TIMESTAMPTZ,
    UNIQUE (bulk_operation_id, issue_key),
    CONSTRAINT chk_bulk_operation_items_failed_reason
        CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
);

CREATE INDEX idx_bulk_operation_items_bulk_operation_id ON bulk_operation_items (bulk_operation_id);
CREATE INDEX idx_bulk_operation_items_operation_status ON bulk_operation_items (bulk_operation_id, status);

-- ═══════════════════════════════════════════════════════════════════════════
-- V009: components 테이블 (FR-CM-01 프로젝트별 컴포넌트)
-- 원본: db/migration/issue-tracking/V009__components.sql
-- jOOQ: Components.ID/PROJECT_ID/NAME/DESCRIPTION/LEAD_USER_ID/CREATED_AT/UPDATED_AT/DELETED_AT 생성 대상
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE components (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects(id),
    name          VARCHAR(255) NOT NULL,
    description   TEXT         NULL,
    lead_user_id  UUID         NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  components              IS '프로젝트별 컴포넌트(하위 영역 분류). 같은 BC 라 projects 실 FK 적용 (FR-CM-01).';
COMMENT ON COLUMN components.project_id   IS '소속 프로젝트 (projects.id). 같은 BC(issue-tracking) 이므로 실 FK 적용.';
COMMENT ON COLUMN components.name         IS '컴포넌트 이름. 활성(deleted_at IS NULL) 기준 프로젝트 내 유일 (부분 유니크 인덱스).';
COMMENT ON COLUMN components.description  IS '컴포넌트 설명 (선택).';
COMMENT ON COLUMN components.lead_user_id IS 'identity-access BC users.id 대응 컴포넌트 리드. BC 격리로 FK 미적용 — ApplicationService 가 존재 guard.';
COMMENT ON COLUMN components.deleted_at   IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3). 삭제 후 동명 재생성 허용.';

CREATE INDEX idx_components_project_id ON components(project_id);

CREATE UNIQUE INDEX ux_components_project_id_name_active
    ON components(project_id, name) WHERE deleted_at IS NULL;

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
    lead_user_id  UUID         NULL,
    require_2fa   BOOLEAN      NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
COMMENT ON TABLE  projects                IS '이슈 컨테이너. key 는 영구 보존 (DATA.md §1.1).';
COMMENT ON COLUMN projects.key            IS '프로젝트 접두사 — 대문자로 시작, 대문자+숫자 2~10자 (예: BTS, ATLAS1). 이슈 키 생성의 기반.';
COMMENT ON COLUMN projects.key_sequence   IS '다음 이슈에 부여할 일련번호. 이슈 생성 시 ApplicationService 가 SELECT FOR UPDATE 후 증가.';
COMMENT ON COLUMN projects.lead_user_id   IS 'identity-access BC users.id 대응 프로젝트 리드. BC 격리로 FK 미적용 — ApplicationService 가 존재 guard.';
COMMENT ON COLUMN projects.require_2fa     IS '민감 프로젝트 여부 — true 면 멤버는 MFA(2FA) 강제 대상 (FR-MF-04). SYSTEM_ADMIN 만 토글.';
COMMENT ON COLUMN projects.deleted_at     IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3).';

-- 3. issues 테이블
CREATE TABLE issues (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key                VARCHAR(20)  NOT NULL UNIQUE CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$'),
    project_id         UUID         NOT NULL REFERENCES projects(id),
    summary            VARCHAR(255) NOT NULL,
    reporter_id        UUID         NOT NULL,
    current_state_key  VARCHAR(50)  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 1,
    -- parent_id: V021 미러 (FR-LK-01). 구조적 parent-child 자기참조 FK. NULL=최상위.
    parent_id          UUID         NULL REFERENCES issues(id),
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
-- V021 미러 (FR-LK-01): parent_id FK 인덱스 — 부모→자식(서브태스크) 조회용.
CREATE INDEX idx_issues_parent_id ON issues(parent_id);

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

-- ═══════════════════════════════════════════════════════════════════════════
-- V010: versions 테이블 (FR-VR-01 프로젝트별 버전)
-- V016: status / released_at 컬럼 + ck_versions_status (FR-VR-02 버전 상태 전이)
-- 원본: db/migration/issue-tracking/V010__versions.sql + V016__version_status.sql
-- jOOQ: Versions.ID/PROJECT_ID/NAME/DESCRIPTION/START_DATE/RELEASE_DATE/STATUS/RELEASED_AT/CREATED_AT/UPDATED_AT/DELETED_AT 생성 대상
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE versions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects(id),
    name          VARCHAR(255) NOT NULL,
    description   TEXT         NULL,
    start_date    DATE         NULL,
    release_date  DATE         NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'UNRELEASED',
    released_at   TIMESTAMPTZ  NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL,
    CONSTRAINT ck_versions_status CHECK (status IN ('UNRELEASED', 'RELEASED', 'ARCHIVED'))
);
COMMENT ON TABLE  versions              IS '프로젝트별 버전(릴리스 단위). 같은 BC 라 projects 실 FK 적용 (FR-VR-01).';
COMMENT ON COLUMN versions.project_id   IS '소속 프로젝트 (projects.id). 같은 BC(issue-tracking) 이므로 실 FK 적용.';
COMMENT ON COLUMN versions.name         IS '버전 이름. 활성(deleted_at IS NULL) 기준 프로젝트 내 유일 (부분 유니크 인덱스).';
COMMENT ON COLUMN versions.description  IS '버전 설명 (선택).';
COMMENT ON COLUMN versions.start_date   IS '버전 시작 예정일 (선택). 날짜 단위라 DATE.';
COMMENT ON COLUMN versions.release_date IS '버전 릴리스 예정일 (선택). 날짜 단위라 DATE.';
COMMENT ON COLUMN versions.status       IS '버전 상태 (UNRELEASED/RELEASED/ARCHIVED). 신규 버전은 UNRELEASED 로 시작 (FR-VR-02).';
COMMENT ON COLUMN versions.released_at  IS 'RELEASED 진입 시각 (Clock 주입). UNRELEASED 진입 시 NULL, ARCHIVED 진입 시 직전값 유지 (FR-VR-02).';
COMMENT ON COLUMN versions.deleted_at   IS 'NULL=활성, NOT NULL=삭제됨. 소프트 삭제 (DATA.md §3). 삭제 후 동명 재생성 허용.';

CREATE INDEX idx_versions_project_id ON versions(project_id);

CREATE UNIQUE INDEX ux_versions_project_id_name_active
    ON versions(project_id, name) WHERE deleted_at IS NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- V011: resolutions 테이블 + 표준 5종 seed + issues.resolution_id 컬럼 (FR-IS-07)
-- 원본: db/migration/issue-tracking/V011__resolutions.sql
-- jOOQ: Resolutions.ID/KEY/NAME/DESCRIPTION/DISPLAY_ORDER/IS_STANDARD/CREATED_AT/UPDATED_AT/DELETED_AT
--       + Issues.RESOLUTION_ID 생성 대상
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE resolutions (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key            TEXT         NOT NULL UNIQUE,
    name           TEXT         NOT NULL,
    description    TEXT         NULL,
    display_order  INT          NOT NULL,
    is_standard    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ  NULL
);

INSERT INTO resolutions (id, key, name, description, display_order, is_standard) VALUES
    ('00000000-0000-4000-8000-000000000001', 'fixed',           'Fixed',            '수정 완료',              1, true),
    ('00000000-0000-4000-8000-000000000002', 'wontfix',         'Won''t Fix',       '수정하지 않기로 결정',   2, true),
    ('00000000-0000-4000-8000-000000000003', 'duplicate',       'Duplicate',        '중복 이슈',              3, true),
    ('00000000-0000-4000-8000-000000000004', 'cannotreproduce', 'Cannot Reproduce', '재현 불가',              4, true),
    ('00000000-0000-4000-8000-000000000005', 'done',            'Done',             '완료',                   5, true);

-- BC 격리로 FK 미적용 — ApplicationService 가 존재 guard (V011 마이그레이션과 동일).
ALTER TABLE issues ADD COLUMN resolution_id UUID NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- V012: issue_components 연결 테이블 (FR-CM-02 이슈↔컴포넌트 N:M)
-- 원본: db/migration/issue-tracking/V012__issue_components.sql
-- jOOQ: IssueComponents.ISSUE_ID/COMPONENT_ID/CREATED_AT 생성 대상
-- ═══════════════════════════════════════════════════════════════════════════

-- 관계 테이블이라 소프트 삭제 없음(연결 해제 = 행 DELETE). issues / components 양쪽 실 FK + ON DELETE CASCADE
-- (순수 관계라 양쪽 엔티티 하드 삭제 시 고아 연결 자동 정리. prod 소프트삭제라 미발화).
CREATE TABLE issue_components (
    issue_id     UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    component_id UUID        NOT NULL REFERENCES components(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (issue_id, component_id)
);
COMMENT ON TABLE  issue_components              IS '이슈↔컴포넌트 N:M 연결. 관계 테이블이라 소프트 삭제 없음 (FR-CM-02).';
COMMENT ON COLUMN issue_components.issue_id     IS '연결된 이슈 (issues.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_components.component_id IS '연결된 컴포넌트 (components.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_components.created_at   IS '연결 생성 시각. TIMESTAMPTZ (DATA.md §4).';

-- FK 인덱스 (DATA.md §7). component_id 만 추가: 복합 PK 선두 issue_id 는 PK 인덱스가 커버,
-- component_id 는 PK 후미라 역방향(컴포넌트→이슈) 조인에 단독 인덱스가 필요.
CREATE INDEX idx_issue_components_component_id ON issue_components(component_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- V014: issues.security_level_id UUID NULL 컬럼 추가 (FR-PM-06 이슈 보안 수준)
-- 원본: db/migration/issue-tracking/V014__issue_security_level.sql
-- jOOQ: Issues.SECURITY_LEVEL_ID 생성 대상 (이 미러가 빠지면 상수 미생성 → repository 컴파일 불가)
-- ═══════════════════════════════════════════════════════════════════════════

-- 등급 소유 BC=identity-access (issue_security_levels.id). BC 격리로 FK 미적용 — V007 assignee 동형.
-- null=등급 미지정(공개). 판정은 ApplicationService/cross-BC 포트가 수행.
ALTER TABLE issues ADD COLUMN security_level_id UUID NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- V015: custom_field_definitions/custom_field_options 테이블 + issues.custom_fields JSONB + GIN (FR-IS-10 커스텀 필드)
-- 원본: db/migration/issue-tracking/V015__custom_fields.sql
-- jOOQ: CustomFieldDefinitions / CustomFieldOptions 테이블 + Issues.CUSTOM_FIELDS 상수 생성 대상
--       (이 미러가 빠지면 상수/테이블 미생성 → repository 컴파일 불가 — jooq-init-codegen-mirror)
-- ═══════════════════════════════════════════════════════════════════════════

-- 프로젝트별 커스텀 필드 정의. 같은 BC 라 projects 실 FK 적용. deleted_at 소프트 삭제.
CREATE TABLE custom_field_definitions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects(id),
    key           TEXT         NOT NULL,
    name          TEXT         NOT NULL,
    description   TEXT         NULL,
    field_type    TEXT         NOT NULL,
    required      BOOLEAN      NOT NULL DEFAULT FALSE,
    display_order INT          NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
CREATE INDEX idx_custom_field_definitions_project_id ON custom_field_definitions(project_id);
CREATE UNIQUE INDEX ux_custom_field_definitions_project_key_active
    ON custom_field_definitions(project_id, key) WHERE deleted_at IS NULL;

-- 선택형 필드의 선택지. 정의에 종속, FK ON DELETE CASCADE.
CREATE TABLE custom_field_options (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    field_id      UUID         NOT NULL REFERENCES custom_field_definitions(id) ON DELETE CASCADE,
    value         TEXT         NOT NULL,
    label         TEXT         NOT NULL,
    display_order INT          NOT NULL,
    UNIQUE (field_id, value)
);
CREATE INDEX idx_custom_field_options_field_id ON custom_field_options(field_id);

-- 커스텀 필드 값 저장 JSONB + GIN 인덱스. NOT NULL DEFAULT '{}'.
ALTER TABLE issues ADD COLUMN custom_fields JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX idx_issues_custom_fields ON issues USING GIN (custom_fields);

-- ═══════════════════════════════════════════════════════════════════════════
-- V017: issue_affects_versions / issue_fix_versions 연결 테이블 (FR-VR-03 이슈↔버전 N:M)
-- 원본: db/migration/issue-tracking/V017__issue_version_links.sql
-- jOOQ: IssueAffectsVersions / IssueFixVersions 테이블 + ISSUE_ID/VERSION_ID/CREATED_AT 상수 생성 대상
--       (이 미러가 빠지면 상수/테이블 미생성 → repository 컴파일 불가 — jooq-init-codegen-mirror)
-- ═══════════════════════════════════════════════════════════════════════════

-- 관계 테이블이라 소프트 삭제 없음(연결 해제 = 행 DELETE). issues / versions 양쪽 실 FK + ON DELETE CASCADE
-- (순수 관계라 양쪽 엔티티 하드 삭제 시 고아 연결 자동 정리. prod 소프트삭제라 미발화).
CREATE TABLE issue_affects_versions (
    issue_id   UUID        NOT NULL REFERENCES issues(id)   ON DELETE CASCADE,
    version_id UUID        NOT NULL REFERENCES versions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (issue_id, version_id)
);
COMMENT ON TABLE  issue_affects_versions            IS '이슈↔영향받는 버전 N:M 연결. 관계 테이블이라 소프트 삭제 없음 (FR-VR-03).';
COMMENT ON COLUMN issue_affects_versions.issue_id   IS '연결된 이슈 (issues.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_affects_versions.version_id IS '영향받는 버전 (versions.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_affects_versions.created_at IS '연결 생성 시각. TIMESTAMPTZ (DATA.md §4).';

-- FK 인덱스 (DATA.md §7). version_id 만 추가: 복합 PK 선두 issue_id 는 PK 인덱스가 커버,
-- version_id 는 PK 후미라 역방향(버전→이슈) 조인에 단독 인덱스가 필요.
CREATE INDEX idx_issue_affects_versions_version_id ON issue_affects_versions(version_id);

-- issue_fix_versions 는 issue_affects_versions 와 구조 동일(의미만 다름: 수정 예정 버전).
CREATE TABLE issue_fix_versions (
    issue_id   UUID        NOT NULL REFERENCES issues(id)   ON DELETE CASCADE,
    version_id UUID        NOT NULL REFERENCES versions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (issue_id, version_id)
);
COMMENT ON TABLE  issue_fix_versions            IS '이슈↔수정 예정 버전 N:M 연결. 관계 테이블이라 소프트 삭제 없음 (FR-VR-03).';
COMMENT ON COLUMN issue_fix_versions.issue_id   IS '연결된 이슈 (issues.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_fix_versions.version_id IS '수정 예정 버전 (versions.id). 같은 BC 라 실 FK 적용.';
COMMENT ON COLUMN issue_fix_versions.created_at IS '연결 생성 시각. TIMESTAMPTZ (DATA.md §4).';

-- FK 인덱스 (DATA.md §7). version_id 만 추가(복합 PK 후미 컬럼).
CREATE INDEX idx_issue_fix_versions_version_id ON issue_fix_versions(version_id);

-- ═══════════════════════════════════════════════════════════════════════════
-- V018: issue_change_group / issue_change_item 이슈 변경 이력 테이블 (FR-HS-01)
-- 원본: db/migration/issue-tracking/V018__issue_change_history.sql
-- 이 2테이블은 NamedParameterJdbcTemplate 접근(jOOQ 미사용)이지만, 프로젝트 동기화 규칙상 미러 필수.
-- ═══════════════════════════════════════════════════════════════════════════

-- append-only — soft-delete/updated_at 없음. 이력 보존 우선으로 issues/users FK 미적용 (FR-AU-10 패턴).
CREATE TABLE issue_change_group (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issue_id    UUID         NOT NULL,
    issue_key   VARCHAR(20)  NOT NULL,
    actor_id    UUID         NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  issue_change_group            IS '이슈 변경 그룹(한 트랜잭션 단위). append-only — 수정/삭제 불가 (FR-HS-01, DATA.md §3).';
COMMENT ON COLUMN issue_change_group.issue_id   IS '변경된 이슈 (issues.id 대응). 이력 보존 우선으로 FK 미적용 (FR-AU-10 패턴).';
COMMENT ON COLUMN issue_change_group.issue_key  IS '기록 시점 이슈 키 (예: BTS-1). 이슈 이동 후에도 당시 키 보존.';
COMMENT ON COLUMN issue_change_group.actor_id   IS '변경 주체 (users.id 대응). NULL=시스템 자동 변경. FK 미적용.';
COMMENT ON COLUMN issue_change_group.created_at IS '변경 발생 시각. TIMESTAMPTZ (DATA.md §4).';

CREATE INDEX idx_issue_change_group_issue ON issue_change_group (issue_id, created_at DESC, id DESC);

-- FK 는 group_id → issue_change_group(id) 하나만. issues/users FK 없음 (이력 보존 우선).
CREATE TABLE issue_change_item (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id    BIGINT       NOT NULL REFERENCES issue_change_group(id),
    field       VARCHAR(64)  NOT NULL,
    from_value  TEXT         NULL,
    to_value    TEXT         NULL,
    from_label  TEXT         NULL,
    to_label    TEXT         NULL
);
COMMENT ON TABLE  issue_change_item            IS '변경 그룹 내 개별 필드 변경 항목. append-only (FR-HS-01).';
COMMENT ON COLUMN issue_change_item.group_id   IS '소속 변경 그룹 (issue_change_group.id). 같은 테이블군 내 실 FK 적용.';
COMMENT ON COLUMN issue_change_item.field      IS '변경된 필드 식별자 (예: status, assignee). 64자 제한.';
COMMENT ON COLUMN issue_change_item.from_value IS '변경 전 원시 값 (예: state key, user id). NULL=값 없음.';
COMMENT ON COLUMN issue_change_item.to_value   IS '변경 후 원시 값. NULL=값 없음(필드 비움).';
COMMENT ON COLUMN issue_change_item.from_label IS '변경 전 표시용 라벨 (사람이 읽는 값). NULL=라벨 없음.';
COMMENT ON COLUMN issue_change_item.to_label   IS '변경 후 표시용 라벨. NULL=라벨 없음.';

-- FK 인덱스 (DATA.md §7). 그룹→항목 조인용.
CREATE INDEX idx_issue_change_item_group ON issue_change_item (group_id);

-- 필드별 이력 필터 인덱스.
CREATE INDEX idx_issue_change_item_field ON issue_change_item (field);

-- ═══════════════════════════════════════════════════════════════════════════
-- V019: issue_templates 테이블 (FR-TM-01 프로젝트+타입별 이슈 본문 템플릿)
-- 원본: db/migration/issue-tracking/V019__issue_templates.sql
-- jOOQ: IssueTemplates 테이블 + ID/PROJECT_ID/ISSUE_TYPE_ID/NAME/CONTENT/... 상수 생성 대상
--       (이 미러가 빠지면 상수/테이블 미생성 → repository 컴파일 불가 — jooq-init-codegen-mirror)
-- ═══════════════════════════════════════════════════════════════════════════

-- 프로젝트+이슈 타입 조합별 이슈 본문 템플릿. 같은 BC 라 projects/issue_types 실 FK 적용. deleted_at 소프트 삭제.
CREATE TABLE issue_templates (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects(id),
    issue_type_id BIGINT       NOT NULL REFERENCES issue_types(id),
    name          VARCHAR(100) NOT NULL,
    content       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);
CREATE INDEX idx_issue_templates_project_id    ON issue_templates(project_id);
CREATE INDEX idx_issue_templates_issue_type_id ON issue_templates(issue_type_id);
CREATE UNIQUE INDEX ux_issue_templates_project_type_active
    ON issue_templates(project_id, issue_type_id) WHERE deleted_at IS NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- V021: issue_links 테이블 + issues.parent_id 컬럼 (FR-LK-01 이슈 링크/계층)
-- 원본: db/migration/issue-tracking/V021__issue_links_and_parent.sql
-- jOOQ: IssueLinks 테이블 + ID/SOURCE_ID/TARGET_ID/LINK_TYPE/CREATED_AT 상수 생성 대상.
--       issues.PARENT_ID 컬럼 상수도 추가 (이 미러가 빠지면 상수/테이블 미생성 → repository 컴파일 불가 — jooq-init-codegen-mirror).
-- 주의: parent_id 컬럼 + idx_issues_parent_id 는 위 issues 테이블 정의/인덱스에 인라인 미러됨 (V020 require_2fa 동형).
-- ═══════════════════════════════════════════════════════════════════════════

-- 이슈↔이슈 방향성 링크(blocks/relates/duplicates/clones). 관계 테이블이라 소프트 삭제 없음(해제 = 행 DELETE).
-- issues 양쪽 실 FK + ON DELETE CASCADE (순수 관계라 이슈 하드 삭제 시 고아 링크 자동 정리. prod 소프트삭제라 미발화).
-- created_by 없음(SDD §5.7 정합). source/target 은 issues.id 가 UUID 라 UUID FK (ADR deviation).
CREATE TABLE issue_links (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id  UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    target_id  UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    link_type  VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_issue_links_no_self CHECK (source_id <> target_id),
    CONSTRAINT chk_issue_links_type    CHECK (link_type IN ('blocks', 'relates', 'duplicates', 'clones')),
    CONSTRAINT uq_issue_links          UNIQUE (source_id, target_id, link_type)
);

-- FK 인덱스 (DATA.md §7). source/target 양쪽 단독 조회(나가는/들어오는 링크)에 쓰여 둘 다 추가.
CREATE INDEX idx_issue_links_source_id ON issue_links(source_id);
CREATE INDEX idx_issue_links_target_id ON issue_links(target_id);

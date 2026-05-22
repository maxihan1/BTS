-- jOOQ codegen 전용 통합 init SQL — V001 + V002 순서대로 적용.
-- Flyway migration 파일(db/migration/)과 별개로 관리되며, generateJooq 태스크의 TC_INITSCRIPT 로 사용됨.
-- TC_INITSCRIPT 는 단일 파일만 지원하므로 두 마이그레이션을 여기에 통합한다.
-- 이미지: quay.io/tembo/pg16-pgmq:latest (ADR 2026-05-22-pgmq-postgres-image 채택 결정).
-- pgmq 스키마는 jOOQ codegen 대상에서 제외 — 호출은 raw SQL (dsl.execute("SELECT pgmq.send(...)")).

-- ═══════════════════════════════════════════════════════════════════════════
-- V001: issue-tracking 초기 스키마 (projects, issues, issue_key_redirects)
-- 원본: db/migration/V001__issues_initial.sql
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
-- 원본: db/migration/V002__pgmq_queue_issue_events.sql
-- 이미지: quay.io/tembo/pg16-pgmq:latest — pgmq 사전 설치됨 (ADR 2026-05-22-pgmq-postgres-image).
-- ═══════════════════════════════════════════════════════════════════════════

-- pgmq extension 보장
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_issue_events
SELECT pgmq.create('q_issue_events');

COMMENT ON SCHEMA pgmq IS 'PostgreSQL 기반 메시지 큐 (Kafka 대체). DATA.md §7.2.';

-- issue-tracking V008 — bulk_operations/bulk_operation_items 테이블 및 pgmq 큐 생성 (FR-IS-05 일괄작업)
--
-- bulk_operations: 일괄작업 단건 (actor_id, operation_type, status, payload, 진행 카운터 포함).
-- bulk_operation_items: 일괄작업 대상 이슈 단건. 하나의 bulk_operation 에 N개.
-- q_bulk_operations: 일괄작업 실행 요청 큐 — 워커가 폴링하여 처리.
-- q_bulk_operation_events: 일괄작업 완료/실패 이벤트 큐 — 알림/감사 용도.
--
-- 큐 컨슈머(워커 프로세스)는 backend-engineer 영역. 본 마이그레이션 범위 외.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. bulk_operations 테이블
-- ─────────────────────────────────────────────────────────────────────────────

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

COMMENT ON TABLE  bulk_operations                IS '일괄작업 단건. actor_id 가 요청한 operation_type 작업의 전체 진행 상태를 추적한다. FR-IS-05.';
COMMENT ON COLUMN bulk_operations.operation_type IS '일괄작업 종류. 허용값: BULK_EDIT, BULK_TRANSITION (chk_bulk_operations_operation_type).';
COMMENT ON COLUMN bulk_operations.status         IS '일괄작업 상태. 허용값: PENDING, RUNNING, COMPLETED, FAILED (chk_bulk_operations_status).';
COMMENT ON COLUMN bulk_operations.actor_id       IS '작업을 요청한 사용자 ID. identity-access BC users.id 대응. BC 격리로 FK 미적용.';
COMMENT ON COLUMN bulk_operations.payload        IS '작업 파라미터 JSON. operation_type 별 구조 상이. 예: { "targetState": "done" }.';
COMMENT ON COLUMN bulk_operations.total_count    IS '처리 대상 이슈 총 개수.';
COMMENT ON COLUMN bulk_operations.processed_count IS '처리 완료(성공+실패) 건수.';
COMMENT ON COLUMN bulk_operations.succeeded_count IS '처리 성공 건수.';
COMMENT ON COLUMN bulk_operations.failed_count   IS '처리 실패 건수.';
COMMENT ON COLUMN bulk_operations.created_at     IS '작업 생성 시각.';
COMMENT ON COLUMN bulk_operations.started_at     IS '워커가 처리를 시작한 시각. NULL=아직 미시작.';
COMMENT ON COLUMN bulk_operations.completed_at   IS '작업이 완료(성공 또는 실패)된 시각. NULL=진행 중.';

-- actor_id 조회 인덱스 (본인 작업 목록 조회용)
CREATE INDEX idx_bulk_operations_actor_id ON bulk_operations (actor_id);

-- status 조회 인덱스 (워커 폴링 — PENDING 상태 픽업용)
CREATE INDEX idx_bulk_operations_status ON bulk_operations (status);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. bulk_operation_items 테이블
-- ─────────────────────────────────────────────────────────────────────────────

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

COMMENT ON TABLE  bulk_operation_items                   IS '일괄작업 대상 이슈 단건. bulk_operations 1건에 N개.';
COMMENT ON COLUMN bulk_operation_items.bulk_operation_id IS 'bulk_operations.id FK.';
COMMENT ON COLUMN bulk_operation_items.issue_key         IS '대상 이슈 키. 예: BTS-42. 정규식 CHECK (chk_bulk_operation_items_issue_key). UNIQUE(bulk_operation_id, issue_key) — 동일 작업 내 중복 처리 방지.';
COMMENT ON COLUMN bulk_operation_items.status            IS '단건 처리 상태. 허용값: PENDING, SUCCEEDED, FAILED (chk_bulk_operation_items_status).';
COMMENT ON COLUMN bulk_operation_items.failure_reason    IS '실패 시 사유 코드(FailureReasonCode). status=FAILED 이면 NOT NULL 강제 (chk_bulk_operation_items_failed_reason). NULL=성공 또는 미처리.';
COMMENT ON COLUMN bulk_operation_items.processed_at      IS '단건 처리 완료 시각. NULL=미처리.';

-- FK 인덱스 (PostgreSQL 은 FK 에 자동 인덱스 생성 안 함)
CREATE INDEX idx_bulk_operation_items_bulk_operation_id ON bulk_operation_items (bulk_operation_id);

-- (bulk_operation_id, status) 복합 인덱스 — 특정 작업의 FAILED 항목 조회용
CREATE INDEX idx_bulk_operation_items_operation_status ON bulk_operation_items (bulk_operation_id, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. pgmq 큐 생성
-- ─────────────────────────────────────────────────────────────────────────────

-- 일괄작업 실행 요청 큐 — 워커가 PENDING bulk_operations 를 폴링하여 처리
SELECT pgmq.create('q_bulk_operations');

-- 일괄작업 완료 이벤트 큐 — 알림·감사 등 다운스트림 소비
SELECT pgmq.create('q_bulk_operation_events');

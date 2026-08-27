-- bulk_operations.operation_type CHECK 에 STATUS_MIGRATION 을 더한다 (FR-WF-07 상태 이관 · 로드맵 PR 7)

-- CHECK 의 허용값 목록은 제자리 확장이 불가능하다. DROP 후 재생성이 유일한 경로다.
-- 제약 이름은 V008 의 것을 그대로 재사용한다 — 이름을 바꾸면 어느 쪽이 진짜인지 다음 사람이 못 푼다.
ALTER TABLE bulk_operations
    DROP CONSTRAINT chk_bulk_operations_operation_type;

ALTER TABLE bulk_operations
    ADD CONSTRAINT chk_bulk_operations_operation_type
        CHECK (operation_type IN ('BULK_EDIT', 'BULK_TRANSITION', 'STATUS_MIGRATION'))
        NOT VALID;

ALTER TABLE bulk_operations
    VALIDATE CONSTRAINT chk_bulk_operations_operation_type;

COMMENT ON COLUMN bulk_operations.operation_type IS '일괄작업 종류. 허용값: BULK_EDIT, BULK_TRANSITION, STATUS_MIGRATION (chk_bulk_operations_operation_type).';

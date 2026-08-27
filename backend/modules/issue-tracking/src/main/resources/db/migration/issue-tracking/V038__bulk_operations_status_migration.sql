-- bulk_operations.operation_type CHECK 에 STATUS_MIGRATION 을 더한다 (FR-WF-07 상태 이관 · 로드맵 PR 7)
--
-- 바뀌는 것은 허용값 2 → 3 뿐이다. 컬럼·테이블·인덱스 변경 0 · 데이터 변경 0.
--
-- ── 왜 DROP → ADD 인가 ───────────────────────────────────────────────────────
-- CHECK 의 허용값 목록은 제자리 확장이 불가능하다. DROP 후 같은 이름으로 재생성이 유일한 경로다.
-- 제약 이름은 V008 의 chk_bulk_operations_operation_type 을 그대로 재사용한다 —
-- 이름을 바꾸면 어느 쪽이 진짜인지 다음 사람이 못 푼다. 컬럼 COMMENT 도 그 이름을 그대로 인용한다.
--
-- ── 왜 NOT VALID → VALIDATE 2단계인가 ────────────────────────────────────────
-- ADD CONSTRAINT ... CHECK 한 방은 ACCESS EXCLUSIVE 락을 쥔 채 전체 행을 스캔한다.
-- NOT VALID 는 그 스캔을 건너뛰고 신규·변경 행에만 즉시 적용되며,
-- VALIDATE CONSTRAINT 는 SHARE UPDATE EXCLUSIVE 로 내려가 읽기·쓰기를 막지 않고 기존 행을 검사한다.
-- 이 변경은 술어가 넓어지기만 한다 — 구 허용값 ⊂ 신 허용값이므로 기존 행의 위반은 원리적으로 0 이다.
-- NOT VALID 가 안전한 것이 여기서는 우연이 아니라 구조적으로 보장된다.
--
-- ★한계를 숨기지 않는다. Flyway 는 마이그레이션 1개를 단일 트랜잭션으로 감싸므로 아래 3문이
--   한 트랜잭션에 묶이고, DROP/ADD 가 잡은 ACCESS EXCLUSIVE 가 VALIDATE 까지 유지된다.
--   bulk_operations 는 일괄작업 1건당 1행인 운영 로그 규모라 지금은 그 스캔이 문제가 아니다.
--   이 표가 커져 스캔 시간이 문제가 되면 VALIDATE 만 다음 V번호로 떼어 자체 트랜잭션에서 돌려야 한다
--   (DATA.md §4 「롤백 가능성을 항상 고려 · 큰 변경은 분할」). 한 파일 안에서는 락 시간을 못 줄인다.
--
-- ── 롤백 절차 ────────────────────────────────────────────────────────────────
-- 되돌리려면 STATUS_MIGRATION 행이 0건이어야 한다. 먼저 센다.
--   SELECT count(*) FROM bulk_operations WHERE operation_type = 'STATUS_MIGRATION';
-- 0 이면 아래를 되돌림 마이그레이션(새 V번호)으로 적용한다. 이 파일은 고치지 않는다 — checksum 충돌.
--   ALTER TABLE bulk_operations DROP CONSTRAINT chk_bulk_operations_operation_type;
--   ALTER TABLE bulk_operations ADD CONSTRAINT chk_bulk_operations_operation_type
--       CHECK (operation_type IN ('BULK_EDIT', 'BULK_TRANSITION'));
-- 0 이 아니면 되돌리지 말고 앞으로 고친다. 행을 지우는 것은 선택지가 아니다 —
-- bulk_operations 는 누가 무엇을 언제 옮겼는지의 감사 기록이고, DATA.md §1 이 소프트 삭제를 앞세운다.
--
-- 선례 고지. DATA.md §4 에 CHECK 무중단 규칙이 아직 0건이라 이 파일이 그 자리의 첫 선례다.

ALTER TABLE bulk_operations
    DROP CONSTRAINT chk_bulk_operations_operation_type;

ALTER TABLE bulk_operations
    ADD CONSTRAINT chk_bulk_operations_operation_type
        CHECK (operation_type IN ('BULK_EDIT', 'BULK_TRANSITION', 'STATUS_MIGRATION'))
        NOT VALID;

ALTER TABLE bulk_operations
    VALIDATE CONSTRAINT chk_bulk_operations_operation_type;

COMMENT ON COLUMN bulk_operations.operation_type IS '일괄작업 종류. 허용값: BULK_EDIT, BULK_TRANSITION, STATUS_MIGRATION (chk_bulk_operations_operation_type).';

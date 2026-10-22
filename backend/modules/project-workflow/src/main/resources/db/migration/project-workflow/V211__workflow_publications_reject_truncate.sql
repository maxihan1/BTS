-- 발행 이력 append-only 강제에 TRUNCATE 거부를 더한다 (FR-WF-07 D13 · WF-07-5) — 행 트리거가 못 보는 유일한 삭제 경로

-- V208 의 trg_workflow_publications_append_only 는 FOR EACH ROW 라 TRUNCATE 에 발동하지 않는다.
-- `TRUNCATE workflow_publications` · `TRUNCATE workflows CASCADE` 둘 다 예외 없이 이력을 비웠다.
-- 문장 트리거를 한 벌 더 단다. 기존 함수가 TG_OP = 'TRUNCATE' 를 이미 거부하므로 함수는 그대로다.
--
-- 부모 workflows 에는 달지 않는다. CASCADE 로 딸려 비워지는 테이블에도 BEFORE TRUNCATE 가 발동하고,
-- CASCADE 없는 `TRUNCATE workflows` 는 FK 때문에 원래 실패한다 (V208MigrationTest 가 둘 다 못박는다).
-- V208 을 고치지 않는 이유 — 이미 적용된 마이그레이션이라 Flyway checksum 이 깨진다.
CREATE TRIGGER trg_workflow_publications_reject_truncate
    BEFORE TRUNCATE ON workflow_publications
    FOR EACH STATEMENT
EXECUTE FUNCTION reject_workflow_publication_mutation();

COMMENT ON TABLE workflow_publications
    IS '발행 이력. append-only — 트리거가 UPDATE·DELETE(V208)·TRUNCATE(V211)를 거부한다';

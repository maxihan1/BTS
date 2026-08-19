-- workflows 편집 지원 컬럼 4종 (FR-WF-04) — 낙관적 락 · 출처 표기 · 소프트 삭제 · 잠금

-- 컬럼 추가는 전부 DEFAULT 를 동반한다. PostgreSQL 11+ 는 DEFAULT 있는 컬럼 추가에서 테이블을
-- 재작성하지 않으므로 운영 중 적용이 안전하다 (DATA.md §4).
ALTER TABLE workflows
    ADD COLUMN version    BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN origin     TEXT        NOT NULL DEFAULT 'CUSTOM',
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN is_locked  BOOLEAN     NOT NULL DEFAULT FALSE;

ALTER TABLE workflows
    ADD CONSTRAINT ck_workflows_origin CHECK (origin IN ('SEED', 'CUSTOM'));

COMMENT ON COLUMN workflows.version    IS '낙관적 락 버전. 편집 화면이 동시 수정 충돌을 감지하는 데 쓴다';
COMMENT ON COLUMN workflows.origin     IS 'SEED = 시드 YAML 에서 온 표준 워크플로우 · CUSTOM = 사용자가 만든 것. 「기본값으로 복원」 대상을 이 값으로 가른다';
COMMENT ON COLUMN workflows.deleted_at IS '소프트 삭제 시각. NULL 이면 살아 있다';
COMMENT ON COLUMN workflows.is_locked  IS '편집 잠금. 발행 중 등 일시적으로 수정을 막을 때 쓴다';

-- 이미 적재된 표준 4종을 SEED 로 표기한다. 이 목록은 YamlSeedService.standardWorkflowKeys 와
-- 같아야 하며, 두 목록이 갈라지지 않도록 StandardWorkflowKeyParityTest 가 집합 대조한다.
UPDATE workflows
   SET origin = 'SEED'
 WHERE key IN ('software-default', 'bug-tracking', 'simple', 'kanban-basic');

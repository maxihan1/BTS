-- 워크플로우 초안(JSONB)과 발행 이력(append-only)을 도입한다 (FR-WF-07) — 편집 중간 상태가 운영에 새지 않게

-- ## 무엇이 문제였나 — 편집이 곧 배포였다
--
--   [V207 까지]                              [V208 이후]
--   화면이 정규 테이블을 직접 고친다          초안(JSONB)을 따로 두고 발행할 때만 반영
--         ↑ 저장하는 순간 운영에 반영됐다            ↑ 발행 전에는 런타임이 옛 정의를 본다
--
-- 사용 중인 워크플로우를 직접 고치면 **편집 중간 상태가 그대로 운영에 샌다.** 상태를 지우고
-- 전환을 다시 잇는 사이에 이슈가 전환을 시도하면 반쯤 고쳐진 정의로 계산된다.
-- 설계 정본은 ADR 2026-08-18-workflow-db-as-source-of-truth §D4.
--
-- ## 왜 테이블 복제가 아니라 JSONB 인가
--
-- 초안을 표현하는 세 안 중 (b) 를 택했다.
--   (a) workflows 행 복제 + status 컬럼   상태·전환·규칙 테이블을 전부 초안용으로 복제해야 해
--                                          FK·UNIQUE 가 두 배로 복잡해진다
--   (b) workflow_drafts JSONB      ★      테이블 복제 0 · 「발행 전엔 런타임에 절대 안 샌다」가
--                                          구조적으로 보장된다 (읽기 경로가 이 테이블을 안 본다)
--   (c) 버전 테이블 전면                   이력·롤백 완비. 범위 초과
--
-- (b) 의 대가는 **초안 상태에서 DB 제약이 invariant 를 지키지 못한다**는 것이다. 그 자리는
-- 애플리케이션의 `Workflow.of()` factory 가 진다 — 초안 저장과 발행이 **같은 factory** 를
-- 통과해야 「초안만 통과하고 발행에서 터지는」 경우가 생기지 않는다.
--
-- ## 사용자 FK 를 걸지 않는 이유
-- `updated_by`·`published_by` 는 users 를 참조하지만 FK 를 걸지 않는다. users 는 identity-access
-- BC 소유이고 이 BC 가 직접 참조하면 모듈 경계를 넘는다. `bulk_operations.actor_id`(V008)가
-- 같은 이유로 같은 선택을 했다 — BC 격리가 참조 무결성보다 앞선다.
--
-- ## 인덱스를 CONCURRENTLY 로 만들지 않는 이유
-- Flyway 트랜잭션 안에서는 쓸 수 없고 대상 테이블이 신규라 비어 있다
-- (V203·V206·V207 과 같은 판단 · ADR 2026-05-21-v001-initial-schema-non-concurrent).

-- ── ① workflow_drafts — 워크플로우당 초안 1개 ────────────────────────────────
-- workflow_id 를 그대로 PK 로 쓴다. 초안이 둘이면 「발행 대상이 어느 쪽인가」를 정할 수 없고,
-- 별도 id 를 두면 그 질문에 답하려고 「최신 초안」 규칙을 또 만들어야 한다.
CREATE TABLE workflow_drafts (
    workflow_id  UUID        PRIMARY KEY
                             CONSTRAINT fk_workflow_drafts_workflow
                             REFERENCES workflows (id) ON DELETE CASCADE,
    definition   JSONB       NOT NULL,
    base_version BIGINT      NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by   UUID
);

COMMENT ON TABLE workflow_drafts
    IS '워크플로우 편집 초안. 발행 전까지 런타임이 이 테이블을 읽지 않는다 — 그것이 초안의 정의다';
COMMENT ON COLUMN workflow_drafts.definition
    IS '초안 정의 전체(상태·전환·규칙). 애플리케이션 Workflow.of() 가 invariant 를 검증한다';
COMMENT ON COLUMN workflow_drafts.base_version
    IS '초안을 뜬 시점의 workflows.version. 발행 시 현재 version 과 다르면 그 사이 누가 먼저 발행한 것이다 (409)';
COMMENT ON COLUMN workflow_drafts.updated_by
    IS '마지막 편집자 user id. users FK 없음 — identity-access BC 소유라 참조하지 않는다 (V008 actor_id 와 같은 판단)';

-- ── ② workflow_publications — 발행 이력 ──────────────────────────────────────
-- version_no 는 워크플로우 안에서 1 부터 증가한다. 전역 시퀀스를 쓰지 않는 것은 「이 워크플로우의
-- 3번째 발행」이 사람이 읽는 단위이기 때문이다.
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

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다).
-- uq_workflow_publications_version 이 (workflow_id, version_no) 선두 컬럼으로 workflow_id 를
-- 이미 덮으므로 단독 인덱스를 따로 만들지 않는다.
CREATE INDEX idx_workflow_publications_published_at
    ON workflow_publications (workflow_id, published_at DESC);

COMMENT ON TABLE workflow_publications
    IS '발행 이력. append-only — 아래 트리거가 UPDATE·DELETE 를 거부한다';
COMMENT ON COLUMN workflow_publications.version_no
    IS '워크플로우 안에서 1 부터 증가하는 발행 회차. 전역이 아니라 워크플로우 단위다';
COMMENT ON COLUMN workflow_publications.definition
    IS '발행 시점의 정의 스냅샷. 되돌리기의 원본이라 이후 편집에 영향받으면 안 된다';

-- ── ③ append-only 강제 — 약속이 아니라 제약으로 ──────────────────────────────
--
-- 「UPDATE·DELETE 경로를 만들지 않는다」는 애플리케이션 약속으로 두면 **아무도 검사하지 않는다.**
-- 나중에 리포지토리에 UPDATE 를 하나 더하는 순간 조용히 깨지고, 깨진 사실이 어디에도 드러나지
-- 않는다. 발행 이력은 감사 기록이고 ADR 2026-08-25-workflow-transition-rule-hard-delete 가
-- append-only 를 계약으로 선언했으므로 계약을 기계가 지키게 한다.
--
-- ★ CASCADE 는 통과시켜야 한다. `ON DELETE CASCADE` 의 자식 삭제도 DELETE 트리거를 발동시키는데,
--   그것까지 막으면 **워크플로우를 영영 지울 수 없다.** 두 경우를 pg_trigger_depth() 로 가른다.
--
--     사람이 직접 실행한 DELETE                  depth = 1   ← 막는다
--     FK CASCADE 가 부른 DELETE (RI 트리거 경유)  depth > 1   ← 통과시킨다
--
--   「사람이 이력을 지우는 것」과 「주인이 사라져 따라가는 것」은 다른 행위다.
CREATE OR REPLACE FUNCTION reject_workflow_publication_mutation()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        -- 부모 workflows 행이 지워져 CASCADE 로 따라온 삭제다. 고아를 남기지 않으려면 통과시킨다.
        RETURN OLD;
    END IF;

    RAISE EXCEPTION
        'workflow_publications 는 append-only 다. % 는 허용되지 않는다 — '
        '발행 이력을 고치면 「무엇을 언제 발행했는가」가 사라진다. '
        '되돌리려면 이전 정의를 새 발행으로 다시 올린다.',
        TG_OP;
END;
$$;

COMMENT ON FUNCTION reject_workflow_publication_mutation()
    IS '발행 이력 append-only 강제. CASCADE 삭제(pg_trigger_depth > 1)만 통과시킨다';

CREATE TRIGGER trg_workflow_publications_append_only
    BEFORE UPDATE OR DELETE ON workflow_publications
    FOR EACH ROW
EXECUTE FUNCTION reject_workflow_publication_mutation();

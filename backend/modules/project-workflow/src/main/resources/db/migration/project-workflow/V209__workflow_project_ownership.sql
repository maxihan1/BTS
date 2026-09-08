-- workflows·workflow_schemes 에 소유 프로젝트를 넣고 key 유니크를 소유별로 가른다 (FR-WF-08)

-- ## 무엇을 여는가
--
-- 지금 워크플로우·스킴은 전역 자원이라 CRUD 가 SYSTEM_ADMIN 전용이다. 프로젝트 관리자가
-- 자기 프로젝트 전용 워크플로우를 만들 수 없다. 소유 컬럼이 없어서 컨트롤러가 권한을 물을 때
-- 「어느 프로젝트 스코프인가」를 답할 수 없기 때문이다 — 그래서 전부 Global 로 하드코딩돼 있다.
--
--   project_id IS NULL      전역 공유 템플릿. 만들고 고치는 것은 SYSTEM_ADMIN
--   project_id = <값>       그 프로젝트 전용. 그 프로젝트의 PROJECT_ADMIN 이 고친다
--
-- Jira Cloud 와 **같은 불변식**이다 — 「공유되지 않은 워크플로우만」 프로젝트 관리자가 편집하고,
-- 공유된 것은 전역 관리자 몫이다(support.atlassian.com, Edit Workflows permission).
-- 스펙 docs/specs/2026-09-08-project-owned-workflows.md.
--
-- ## 이 마이그레이션은 데이터를 옮기지 않는다
--
-- 컬럼만 추가하고 기존 행은 전부 NULL(전역)로 남는다. 표준 스킴 4종(V201)과 표준 워크플로우는
-- 계속 전역이다. 프로젝트 소유 행은 사용자가 만들 때 처음 생긴다 — 백필이 없으므로
-- 이 마이그레이션 자체는 되돌리기 쉽다(아래 롤백 절 참조).
--
-- ## cross-BC — FK 를 걸지 않는다
--
-- projects 테이블은 다른 BC 소유다. V201:47 이 assignments 에 대해 같은 판단을 이미 적어 뒀고
-- V202 가 그 컬럼을 UUID 로 맞췄다. 여기서도 UUID 컬럼 + 인덱스만 두고 FK 는 걸지 않는다.
--
-- ## ★함께 고치는 선재 결함 — workflow_schemes 만 V206 에서 빠져 있었다
--
-- V206 이 statuses·workflows 의 key UNIQUE 를 `WHERE deleted_at IS NULL` 부분 유니크로 바꿔
-- 「소프트 삭제된 행이 key 를 영원히 점유」하는 문제를 닫았는데, **workflow_schemes 는 그 대상에
-- 없었다.** V201:25 의 컬럼 UNIQUE 가 그대로라 스킴을 지우면 같은 key 로 재생성이 불가능하다.
-- statuses·workflows 는 되는데 스킴만 안 되는 비대칭이다.
--
-- 이 마이그레이션이 어차피 그 제약을 다시 쓰므로 같은 자리에서 소프트 삭제 조건을 함께 넣는다.
-- 남겨 두면 「세 테이블 중 둘만 부분 유니크」라는 두 번째 비대칭이 된다.
--
-- ## 인덱스가 넷인 이유 — 조건이 서로 배타적이어야 한다
--
--   전역   UNIQUE (key)              WHERE project_id IS NULL     AND deleted_at IS NULL
--   프로젝트 UNIQUE (project_id, key) WHERE project_id IS NOT NULL AND deleted_at IS NULL
--
-- 한 인덱스로 `UNIQUE (project_id, key)` 만 두면 **전역끼리의 유일성이 깨진다** — Postgres 에서
-- NULL 은 서로 같지 않아 project_id 가 NULL 인 행은 key 가 같아도 충돌하지 않는다. 전역 템플릿의
-- key 는 WorkflowKeyResolver·스킴 매핑·YAML 시드가 단독으로 참조하므로 유일해야 한다.
--
-- 검증은 「컬럼이 생겼다」로는 안 된다. 같은 key 를 전역 1건 + 서로 다른 프로젝트 2건 +
-- 소프트 삭제 1건으로 넣어 **인덱스가 실제로 무는지**를 재야 한다 (D3 검증 테스트).
--
-- ## 롤백
--
-- 컬럼 DROP + 인덱스 원복이다. 되돌리는 시점에 프로젝트 소유 행이 이미 있으면 그 행들이
-- 전역으로 승격되어 key 가 충돌할 수 있다 — 적용 직후에는 대상 0건이라 안전하지만,
-- 사용자가 워크플로우를 만든 뒤라면 먼저 다음으로 중복을 확인해야 한다.
--   SELECT key FROM workflows WHERE deleted_at IS NULL GROUP BY key HAVING count(*) > 1
-- 중복이 있으면 이력 소실 없이 되돌릴 수 없다. Maxi 확인이 필요하다.
--
-- 인덱스를 CONCURRENTLY 로 만들지 않는 것은 V203·V206 과 같다 — Flyway 트랜잭션 안에서는 쓸 수
-- 없고 대상 테이블이 소규모다 (ADR 2026-05-21-v001-initial-schema-non-concurrent).

-- ── 1. 선행 상태 확인 ─────────────────────────────────────────────────────────
-- 기대와 다른 이름을 조용히 지나치지 않는다 (V204·V206 가드 관례).

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
         WHERE tablename = 'workflows' AND indexname = 'uq_workflows_key'
    ) THEN
        RAISE EXCEPTION 'V209. uq_workflows_key 인덱스가 없다. V206 이 만든 부분 유니크가 바뀌었는지 확인할 것';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE table_name = 'workflow_schemes' AND constraint_name = 'workflow_schemes_key_key'
    ) THEN
        RAISE EXCEPTION 'V209. workflow_schemes_key_key 제약이 없다. V201 의 key UNIQUE 선언이 바뀌었는지 확인할 것';
    END IF;
END $$;

-- ── 2. 소유 컬럼 추가 ─────────────────────────────────────────────────────────

ALTER TABLE workflows        ADD COLUMN project_id UUID;
ALTER TABLE workflow_schemes ADD COLUMN project_id UUID;

COMMENT ON COLUMN workflows.project_id        IS 'NULL=전역 공유 템플릿(SYSTEM_ADMIN 소관), 값=그 프로젝트 전용(PROJECT_ADMIN 편집 가능). projects.id 참조하되 cross-BC 라 FK 없음 (FR-WF-08)';
COMMENT ON COLUMN workflow_schemes.project_id IS 'NULL=전역 공유 템플릿(SYSTEM_ADMIN 소관), 값=그 프로젝트 전용(PROJECT_ADMIN 편집 가능). projects.id 참조하되 cross-BC 라 FK 없음 (FR-WF-08)';

-- ── 3. workflows — key 유니크를 소유별로 가른다 ────────────────────────────────

DROP INDEX uq_workflows_key;

CREATE UNIQUE INDEX uq_workflows_key_global
    ON workflows (key)
 WHERE project_id IS NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX uq_workflows_key_project
    ON workflows (project_id, key)
 WHERE project_id IS NOT NULL AND deleted_at IS NULL;

COMMENT ON INDEX uq_workflows_key_global  IS '살아 있는 전역 워크플로우끼리 key 가 유일하다. WorkflowKeyResolver·스킴 매핑·YAML 시드가 key 단독으로 참조하므로 필요하다';
COMMENT ON INDEX uq_workflows_key_project IS '살아 있는 프로젝트 소유 워크플로우는 프로젝트 안에서만 key 가 유일하다. 팀마다 같은 이름을 쓸 수 있다';

-- ── 4. workflow_schemes — 같은 형태 + 소프트 삭제 정합(선재 결함) ──────────────

ALTER TABLE workflow_schemes DROP CONSTRAINT workflow_schemes_key_key;

CREATE UNIQUE INDEX uq_workflow_schemes_key_global
    ON workflow_schemes (key)
 WHERE project_id IS NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX uq_workflow_schemes_key_project
    ON workflow_schemes (project_id, key)
 WHERE project_id IS NOT NULL AND deleted_at IS NULL;

COMMENT ON INDEX uq_workflow_schemes_key_global  IS '살아 있는 전역 스킴끼리 key 가 유일하다. V206 이 statuses·workflows 에만 넣었던 소프트 삭제 조건을 여기서 맞춘다';
COMMENT ON INDEX uq_workflow_schemes_key_project IS '살아 있는 프로젝트 소유 스킴은 프로젝트 안에서만 key 가 유일하다';

-- ── 5. 소유별 목록 조회 인덱스 ────────────────────────────────────────────────
-- 「전역 + 내 프로젝트 것」 필터가 목록 조회의 지배 질의가 된다.

CREATE INDEX ix_workflows_project_active
    ON workflows (project_id)
 WHERE deleted_at IS NULL;

CREATE INDEX ix_workflow_schemes_project_active
    ON workflow_schemes (project_id)
 WHERE deleted_at IS NULL;

-- V201:41 의 ix_workflow_schemes_key_active 는 그대로 둔다 — key 단독 조회(활성)가 여전히 있고,
-- 위 유니크 인덱스들은 조건이 달라 그 질의를 완전히 대체하지 않는다.

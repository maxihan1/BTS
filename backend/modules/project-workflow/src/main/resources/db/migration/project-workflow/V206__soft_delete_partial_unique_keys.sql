-- statuses·workflows 의 key 유니크를 소프트 삭제 제외 부분 인덱스로 바꾼다 (FR-WF-04)

-- ## 무엇이 문제였나 — 같은 테이블 안에서 규칙이 둘로 갈라져 있었다
--
--   [V205 까지]                                  [V206 이후]
--   key   UNIQUE            (전역)               key   UNIQUE WHERE deleted_at IS NULL
--   name  UNIQUE WHERE deleted_at IS NULL        name  UNIQUE WHERE deleted_at IS NULL
--         ↑ 규칙 2벌                                    ↑ 규칙 1벌
--
-- V205 가 소프트 삭제(deleted_at)를 도입했는데 key 는 V203:13 · V200:7 의 **무조건 UNIQUE** 그대로였다.
-- 그 결과 상태를 지우면 죽은 행이 key 를 영원히 점유해 **같은 key 로 재생성이 불가능**했다.
-- 이름은 이미 부분 유니크라 재사용되는데 key 만 안 되는 비대칭이었다.
--
-- 편집기 UI(로드맵 PR 8~10)에서 상태를 지웠다 다시 만드는 것은 일상 조작이고, 그때 나는 409 는
-- 원인인 죽은 행이 화면에 보이지도 지워지지도 않아 사용자가 스스로 벗어날 수 없다.
--
-- ## 방향
-- 제약을 **완화**하는 변경이다. 기존 데이터가 새 규칙을 위반할 수 없으므로 백필도 데이터 가드도 필요 없다.
-- 다만 제약 이름이 기대와 다르면 조용히 지나가지 않도록 DO 블록으로 확인 후 DROP 한다 (V204 가드 관례).
--
-- ## 롤백 주의 — 데이터 의존적이다
-- 이 마이그레이션을 되돌리려면 부분 인덱스를 DROP 하고 컬럼 UNIQUE 를 복원해야 하는데,
-- 그 시점에 **소프트 삭제된 행과 같은 key 를 쓰는 살아 있는 행이 있으면 복원이 실패한다.**
-- 즉 롤백 가능 여부가 그때의 데이터에 달렸다. 적용 시점에는 대상 행이 0건이라 안전하다(실측).
-- 되돌려야 한다면 먼저 `SELECT key FROM statuses GROUP BY key HAVING count(*) > 1` 로 중복을 확인하고
-- 소프트 삭제된 쪽을 물리 삭제해야 한다. 그것은 이력 소실이므로 Maxi 확인이 필요하다.
--
-- 인덱스를 CONCURRENTLY 로 만들지 않는 것은 V203 과 같다 — Flyway 트랜잭션 안에서는 쓸 수 없고
-- 대상 테이블이 소규모다 (ADR 2026-05-21-v001-initial-schema-non-concurrent).

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE table_name = 'statuses' AND constraint_name = 'statuses_key_key'
    ) THEN
        RAISE EXCEPTION 'V206. statuses_key_key 제약이 없다. V203 의 key UNIQUE 선언이 바뀌었는지 확인할 것';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE table_name = 'workflows' AND constraint_name = 'workflows_key_key'
    ) THEN
        RAISE EXCEPTION 'V206. workflows_key_key 제약이 없다. V200 의 key UNIQUE 선언이 바뀌었는지 확인할 것';
    END IF;
END $$;

ALTER TABLE statuses  DROP CONSTRAINT statuses_key_key;
ALTER TABLE workflows DROP CONSTRAINT workflows_key_key;

CREATE UNIQUE INDEX uq_statuses_key
    ON statuses (key)
 WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_workflows_key
    ON workflows (key)
 WHERE deleted_at IS NULL;

COMMENT ON INDEX uq_statuses_key  IS '살아 있는 상태끼리만 key 가 유일하다. 소프트 삭제된 행은 key 를 점유하지 않는다';
COMMENT ON INDEX uq_workflows_key IS '살아 있는 워크플로우끼리만 key 가 유일하다. 소프트 삭제된 행은 key 를 점유하지 않는다';

-- 이슈 cursor pagination keyset seek 가속 커버 부분 인덱스 — FR-API-01. (created_at DESC, id DESC) 활성 이슈.

-- 배경: FR-API-01 GET /api/v1/issues cursor pagination 은 IssueRepository.listWithTypeByCursor 가
--   keyset seek 쿼리 `WHERE (created_at < :c) OR (created_at = :c AND id < :id)` +
--   `ORDER BY created_at DESC, id DESC` 로 다음 페이지를 가져온다 (offset 기반이 아닌 seek 방식 —
--   깊은 페이지에서도 시작점 탐색이 일정).
-- 문제(EXPLAIN 실측): issues 테이블에 (created_at DESC, id DESC) 정렬 순서 인덱스가 없어 seq scan + sort 경로를 탄다.
--   깊은 페이지일수록 정렬 비용이 누적되어 cursor pagination 의 seek 이점이 사라진다.
--
-- 설계(DATA.md §7): (created_at DESC, id DESC) 복합 인덱스로 seek 술어 + ORDER BY 를 단일 index scan 으로 충족.
--   인덱스 정렬 방향을 쿼리 ORDER BY 와 정확히 일치(둘 다 DESC)시켜야 별도 sort 노드 없이 인덱스 순회만으로 페이지를 채운다.
--   id(UUID PK)는 created_at 동률 시 결정적 tie-breaker — keyset 커서가 동일 created_at 행을 건너뛰지 않도록 보장.
--   부분 인덱스(WHERE deleted_at IS NULL): 목록은 항상 활성 이슈만 보므로 idx_issues_project_*_active(V029) 선례와
--   동형으로 활성 행만 커버 → 인덱스 크기 절감 + seek/정렬/필터(deleted_at)를 단일 index scan 으로 결합.
--
-- 인덱스 생성 방식: plain CREATE INDEX(CONCURRENTLY 미사용). Flyway 기본 트랜잭션 내 CONCURRENTLY 비호환
--   (ADR 2026-05-21-v001-initial-schema-non-concurrent). V026/V029/V031 형제 선례와 일관(짧은 락).
CREATE INDEX idx_issues_created_cursor
    ON issues (created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX idx_issues_created_cursor
    IS '이슈 cursor pagination keyset seek 가속 커버 부분 인덱스 — (created_at DESC, id DESC) 활성 이슈. FR-API-01.';

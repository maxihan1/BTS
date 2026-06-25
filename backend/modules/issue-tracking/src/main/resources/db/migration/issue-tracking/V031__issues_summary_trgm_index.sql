-- 이슈 summary 부분일치(`~`/ILIKE) 검색 가속용 pg_trgm trigram GIN 인덱스 — FR-SR-02 D3 (AQL CONTAINS).

-- 배경: AQL(Atlas Query Language) `summary ~ "term"` 은 백엔드에서 jOOQ likeIgnoreCase
--   (IssueRepository.kt:2298 buildSummaryCondition CONTAINS) = `lower(summary) LIKE lower('%term%')` 로 컴파일된다.
--   선행/후행 와일드카드(%term%)가 붙은 LIKE 는 B-tree 인덱스로 가속되지 않으므로 trigram(3글자 조각) GIN 인덱스가 필요하다.
--
-- 표현식 인덱스(lower(summary)) 채택 이유 (plan BLOCKER B1):
--   백엔드 쿼리가 `lower(summary)` 에 대해 LIKE 를 수행하므로, bare 컬럼 인덱스(gin(summary gin_trgm_ops))는
--   표현식 불일치로 플래너가 선택하지 못해 죽은 인덱스가 된다. 인덱스 표현식을 쿼리 표현식과 정확히 일치시켜야 한다.
--   백엔드 코드는 무변경(범위 보존) — 인덱스만 쿼리에 맞춘다.
--
-- 범위: summary 단일 필드만 가속(spec 결정). label `~`(unnest 후 ILIKE)는 trigram 직접 적용이 까다로워 후속으로 분리.
--
-- 인덱스 생성 방식: plain CREATE INDEX(CONCURRENTLY 미사용). 초기 단계 중소 테이블 + Flyway 기본 트랜잭션 내
--   CONCURRENTLY 비호환. V026/V028/V029/V030 형제 선례와 일관(짧은 락).

-- 1. pg_trgm 확장 — gin_trgm_ops opclass 의 전제. 확장 누락 시 인덱스 DDL 이 "operator class does not exist" 로 실패.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. lower(summary) 표현식 trigram GIN 인덱스 — likeIgnoreCase 의 lower() 매칭으로 ILIKE `%term%` 가속.
CREATE INDEX IF NOT EXISTS idx_issues_summary_trgm
    ON issues USING gin (lower(summary) gin_trgm_ops);

COMMENT ON INDEX idx_issues_summary_trgm
    IS 'AQL summary `~`(lower(summary) LIKE lower(%term%)) 부분일치 검색 가속 trigram GIN 인덱스. FR-SR-02 D3.';

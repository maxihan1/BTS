-- issues.summary+description 결합 한글 전문 검색(FTS) — STORED generated tsvector + GIN + description trigram GIN (FR-SR-04).

-- ── 락/테이블 rewrite 영향 (G3) ────────────────────────────────────────────────
-- ADD COLUMN ... GENERATED ALWAYS AS (...) STORED 는 기존 모든 행의 search_vector 값을 즉시 산출해
-- 채워 넣어야 하므로 테이블 full rewrite 를 유발한다(단순 메타데이터 ADD COLUMN 과 달리 무시 못 할 비용).
-- rewrite 동안 issues 에 ACCESS EXCLUSIVE 락이 걸려 읽기/쓰기가 모두 차단된다.
-- 현재 BTS 는 1,000명 규모 단일 호스트 + 소규모 issues 테이블이므로 락 시간은 순간(수십 ms~)이며,
-- V006/V031 형제 인덱스가 plain CREATE INDEX(CONCURRENTLY 미사용, Flyway 단일 트랜잭션)로 짧은 락을
-- 수용한 선례와 동일 판단이다. 대규모 데이터로 성장하면 점검 시간대 적용 또는
-- nullable 컬럼 + 트리거 백필(온라인) 전략으로 재검토한다.
-- 인덱스(idx_issues_search_vector / idx_issues_description_trgm)도 같은 이유로 plain CREATE INDEX 를 따른다.

-- 1. search_vector — summary(제목) + description(본문) 결합 tsvector STORED generated column.
--    'simple' 설정(형태소 분석 없는 단순 토큰화) + 2-인자형 to_tsvector(IMMUTABLE) → generated column 가능.
--    (1-인자형 to_tsvector(regconfig 미지정)은 default_text_search_config 의존으로 STABLE → generated 불가.)
--    coalesce/`||` 모두 immutable 이라 표현식 전체가 immutable → STORED generated 제약 충족.
ALTER TABLE issues
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (
            to_tsvector('simple', coalesce(summary, '') || ' ' || coalesce(description, ''))
        ) STORED;

COMMENT ON COLUMN issues.search_vector IS
    'summary+description 결합 simple tsvector (STORED generated). FTS(search_vector @@ plainto_tsquery) 색인. 앱 미영속 — DB 자동 산출 (FR-SR-04).';

-- 2. search_vector GIN 인덱스 — @@ plainto_tsquery('simple', q) 전문 검색 가속.
CREATE INDEX idx_issues_search_vector ON issues USING gin (search_vector);

COMMENT ON INDEX idx_issues_search_vector IS
    'AQL text `~` FTS(search_vector @@ plainto_tsquery(''simple'', q)) 가속 GIN 인덱스. FR-SR-04.';

-- 3. lower(description) 표현식 trigram GIN 인덱스 — 조사/활용 변형 부분일치(ILIKE) 보강.
--    [B1 — 죽은 인덱스 차단] 백엔드는 DESCRIPTION.likeIgnoreCase 로 `lower("description") LIKE ?` 를 생성한다.
--    인덱스 표현식을 쿼리 표현식과 정확히 일치(bare lower(description), coalesce 없음)시켜야 플래너가 선택한다.
--    V031 idx_issues_summary_trgm(lower(summary) gin_trgm_ops)과 동형 — coalesce 를 끼우면 표현식 불일치로
--    플래너가 인덱스를 무시(죽은 인덱스) → text `~` 의 summary OR description 매칭이 전체 seq scan 으로 전락한다.
CREATE INDEX idx_issues_description_trgm ON issues USING gin (lower(description) gin_trgm_ops);

COMMENT ON INDEX idx_issues_description_trgm IS
    'AQL text `~` description 부분일치(lower(description) LIKE lower(%term%)) 가속 trigram GIN 인덱스. FR-SR-04 (B1 표현식 일치).';

-- 4. description 컬럼 주석 정정 — V006 의 'tsvector 인덱스 추가 금지' 는 FTS 유보 시점 표기였다.
--    FR-SR-04 가 그 추가 시점이므로 주석을 갱신한다. V006 파일 자체는 Flyway checksum 불변이라 손대지 않고,
--    COMMENT ON COLUMN(스키마 메타데이터, 멱등)으로 superseding 한다 (DATA.md — 적용된 마이그레이션 파일 수정 금지).
COMMENT ON COLUMN issues.description IS
    '이슈 본문 (마크다운). FR-SR-04 부터 search_vector(STORED generated tsvector) FTS + lower(description) trigram 으로 색인됨 (논리 책임 search BC, 물리 issue-tracking).';

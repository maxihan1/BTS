-- 이슈 본문·댓글의 HTML 저장 전환 + 검색용 평문 파생 컬럼 (Jira 패리티 캠페인 PR①).

-- ── 왜 백필하지 않는가 ────────────────────────────────────────────────────────
-- 리치 에디터(TipTap) 도입으로 본문 저장 포맷이 markdown → 정화된 HTML 로 바뀐다.
-- 기존 행을 HTML 로 in-place 변환하려면 flexmark 를 호출해야 하는데, 그것은 SQL 로 불가능하고
-- Flyway Java migration 은 이 저장소에 선례가 없다. 더 나쁜 것은 **등록 지점이 둘**이라는 점이다 —
-- 조립 앱은 FlywayAssemblyConfig 가 Flyway.configure() 로 직접 돌고, issue-tracking 단독 테스트는
-- application-test.yml 의 Spring auto-config 로 돈다. 한쪽에만 등록하면 조립 앱에서 백필이 조용히
-- 건너뛰어진다(두 목록이 서로를 검사하지 않는 지배 결함 양식).
--
-- 그래서 백필을 없앤다. 기존 컬럼(markdown)은 **그대로 두고** HTML 컬럼을 새로 둔 뒤,
-- 읽기 지점 한 곳에서 `description_html ?: renderSafe(description)` 로 흡수한다.
-- 그 읽기 지점은 IssueApplicationService.withSingleDetail() 하나뿐이다 — 2026-07-27 에
-- "렌더 생산 지점을 1개로 굳혔다"고 명시된 설계 덕분에 fallback 이 한 줄로 끝난다.
-- 기존 이슈는 편집되는 시점에 자연스럽게 HTML 로 이행하고, markdown 원문은 영구 보존된다.

-- ── 1. issues.description_html — 에디터가 보낸 정화된 HTML ─────────────────────
-- NULL = 아직 HTML 로 저장된 적 없음(= description 의 markdown 을 렌더해 읽는다).
ALTER TABLE issues ADD COLUMN description_html TEXT;

COMMENT ON COLUMN issues.description_html IS
    '이슈 본문 (정화된 HTML). 리치 에디터 저장 경로가 채운다. NULL 이면 description(마크다운)을 렌더해 읽는다 — 읽기 지점 IssueApplicationService.withSingleDetail().';

COMMENT ON COLUMN issues.description IS
    '이슈 본문 (마크다운 원문). description_html 도입 후로는 **레거시 읽기 fallback + CSV import 입력** 경로가 쓴다. 신규 편집은 description_html 을 채운다.';

-- ── 2. issue_body_plain() — 본문 평문 추출의 단일 출처 ────────────────────────
-- ★왜 함수인가. 아래 description_plain 과 search_vector 가 **같은 평문**을 봐야 한다. 그런데
--   PostgreSQL 은 generated column 이 다른 generated column 을 참조하는 것을 금지한다
--   ("cannot use generated column in column generation expression" — 실측). 그래서 search_vector 가
--   description_plain 을 재사용할 수 없고, 식을 그대로 복사하면 한쪽만 고쳤을 때 FTS 와 trigram 이
--   서로 다른 텍스트를 색인하게 된다. 함수로 묶으면 두 컬럼이 **같은 정의 하나**를 호출한다.
-- IMMUTABLE + PARALLEL SAFE 라야 STORED generated column 제약을 충족한다.
-- STRICT 를 붙이지 않는다 — 붙이면 NULL 인자에 즉시 NULL 을 돌려줘 내부 coalesce 가 무력해진다.
CREATE FUNCTION issue_body_plain(html TEXT, md TEXT) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
AS $$ SELECT regexp_replace(coalesce(html, md, ''), '<[^>]*>', '', 'g') $$;

COMMENT ON FUNCTION issue_body_plain(TEXT, TEXT) IS
    '본문 평문 추출 — HTML 이 있으면 태그를 벗기고, 없으면 마크다운 원문을 그대로 쓴다. description_plain 과 search_vector 가 공유하는 단일 정의 (V039).';

-- ── 3. issues.description_plain — 검색용 평문 파생 (STORED generated) ──────────
-- description_html 이 있으면 태그를 벗기고, 없으면 description(마크다운, 태그 없음)이 그대로 통과한다.
-- 앱이 이 값을 쓰지 않으므로(DB 산출) 두 원본 컬럼과 어긋날 수 없다.
-- V032 와 마찬가지로 테이블 rewrite 를 유발한다 — 1,000명 규모 단일 호스트에서 순간 락으로 수용한다.
ALTER TABLE issues
    ADD COLUMN description_plain TEXT
        GENERATED ALWAYS AS (issue_body_plain(description_html, description)) STORED;

COMMENT ON COLUMN issues.description_plain IS
    '본문 평문 (STORED generated). description_html 의 태그를 제거하거나, 없으면 description 을 그대로 쓴다. FTS·trigram 색인 대상 — 앱 미영속.';

-- ── 4. search_vector 재작성 — 평문을 색인하도록 ────────────────────────────────
-- generated column 의 생성식은 ALTER 로 바꿀 수 없다. DROP 후 재생성한다.
-- 의존 인덱스(idx_issues_search_vector)가 함께 사라지므로 다시 만든다.
-- ★description 을 그대로 두면 HTML 로 저장된 본문의 `p`·`strong` 같은 태그명이 검색 토큰이 된다.
-- ★description_plain 을 참조할 수 없어(generated → generated 금지) 같은 함수를 다시 호출한다.
DROP INDEX IF EXISTS idx_issues_search_vector;
ALTER TABLE issues DROP COLUMN search_vector;

ALTER TABLE issues
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (
            to_tsvector(
                'simple',
                coalesce(summary, '') || ' ' || issue_body_plain(description_html, description)
            )
        ) STORED;

COMMENT ON COLUMN issues.search_vector IS
    'summary+description_plain 결합 simple tsvector (STORED generated). FTS(search_vector @@ plainto_tsquery) 색인. 앱 미영속 — DB 자동 산출 (FR-SR-04).';

CREATE INDEX idx_issues_search_vector ON issues USING gin (search_vector);

COMMENT ON INDEX idx_issues_search_vector IS
    'AQL text `~` FTS(search_vector @@ plainto_tsquery(''simple'', q)) 가속 GIN 인덱스. FR-SR-04.';

-- ── 5. description trigram 인덱스 재작성 ──────────────────────────────────────
-- ★[B1 — 죽은 인덱스 차단] V032 의 경고가 그대로 적용된다. 백엔드는 jOOQ 로
--   `lower("description_plain") LIKE ?` 를 생성한다. 인덱스 표현식을 쿼리 표현식과 **정확히**
--   일치(bare lower(description_plain), coalesce 없음)시켜야 플래너가 선택한다.
--   어긋나면 인덱스가 죽고 text `~` 가 seq scan 으로 전락하는데 **테스트는 그대로 통과한다** —
--   그래서 EXPLAIN 단언 테스트가 짝으로 붙는다.
DROP INDEX IF EXISTS idx_issues_description_trgm;

CREATE INDEX idx_issues_description_trgm ON issues USING gin (lower(description_plain) gin_trgm_ops);

COMMENT ON INDEX idx_issues_description_trgm IS
    'AQL text `~` 본문 부분일치(lower(description_plain) LIKE lower(%term%)) 가속 trigram GIN 인덱스. FR-SR-04 (B1 표현식 일치).';

-- ── 6. comments.body_html — 댓글도 같은 구조 ──────────────────────────────────
-- 읽기 지점은 CommentView.of() 하나다.
ALTER TABLE comments ADD COLUMN body_html TEXT;

COMMENT ON COLUMN comments.body_html IS
    '댓글 본문 (정화된 HTML). 리치 에디터 저장 경로가 채운다. NULL 이면 body(마크다운)를 렌더해 읽는다 — 읽기 지점 CommentView.of().';

COMMENT ON COLUMN comments.body IS
    '댓글 본문 (raw markdown 원문). body_html 도입 후로는 레거시 읽기 fallback 경로가 쓴다.';

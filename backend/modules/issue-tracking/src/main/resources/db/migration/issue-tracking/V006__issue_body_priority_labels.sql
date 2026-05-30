-- issue-tracking V006 — issues 5컬럼 추가: description, priority, labels, environment, impact + GIN 인덱스
--
-- 이 마이그레이션이 추가하는 컬럼.
--   1. description  TEXT        NULL  — 이슈 본문 (마크다운). 검색은 search BC 에서 담당, FTS 인덱스 여기서 금지.
--   2. priority     SMALLINT    NOT NULL DEFAULT 3 CHECK (1~5) — 우선순위 (1=가장 높음, 5=가장 낮음). DEFAULT 3 = 보통.
--   3. labels       TEXT[]      NOT NULL DEFAULT '{}' — 레이블 배열. GIN 인덱스 로 배열 원소 검색 지원.
--   4. environment  TEXT        NULL  — 재현 환경 (예: "Chrome 124 / macOS 14").
--   5. impact       SMALLINT    NULL CHECK (1~3) — 영향도 (1=높음, 2=보통, 3=낮음). NULL 허용.
--
-- GIN 인덱스 일반 CREATE INDEX (CONCURRENTLY 미사용) 선택 이유.
--   CREATE INDEX CONCURRENTLY 는 트랜잭션 블록 안에서 실행 불가. Flyway 는 기본적으로
--   각 마이그레이션을 단일 트랜잭션으로 실행하므로 CONCURRENTLY 사용 불가.
--   현재 issues 테이블은 소규모(초기 데이터)이므로 락 우려 없음 — 일반 인덱스 사용.

-- ============================================================
-- 1. 5컬럼 추가
-- ============================================================
ALTER TABLE issues
    ADD COLUMN description  TEXT,
    ADD COLUMN priority     SMALLINT NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
    ADD COLUMN labels       TEXT[]   NOT NULL DEFAULT '{}',
    ADD COLUMN environment  TEXT,
    ADD COLUMN impact       SMALLINT CHECK (impact BETWEEN 1 AND 3);

-- ============================================================
-- 2. GIN 인덱스 — labels 배열 원소 검색용
-- ============================================================
-- ix_ 접두사: 일반 인덱스 명명 규칙 (ux_ = unique, ix_ = 일반).
-- GIN(Generalized Inverted Index): 배열·jsonb 원소 포함 검색(@>, <@ 연산자)에 최적.
CREATE INDEX ix_issues_labels_gin ON issues USING GIN (labels);

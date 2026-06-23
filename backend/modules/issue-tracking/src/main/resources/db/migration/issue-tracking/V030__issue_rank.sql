-- issues.rank(VARCHAR(50) nullable) LexoRank 정렬 키 컬럼 + (project_id, rank) 인덱스 — FR-BL-01
-- 옵션 B(nullable + lazy): 신규 이슈는 rank=NULL, 드래그(rerank) 시 rank 부여. 백필·NOT NULL 없음.
-- 정렬: ORDER BY rank NULLS LAST, created_at, id — NULL(미부여) 이슈는 맨 뒤 생성순.

-- ── 1. rank 컬럼 추가 (nullable — 옵션 B 전환, spec 결정 #5) ──
-- rank: 프로젝트 전역 백로그 정렬 키. 소문자 a-z base-26 알파벳 문자열. 사전순 == 백로그 정렬 순서.
-- VARCHAR(50): SDD §13.2.1. 키 길이 한도(고갈 시 rebalance 트리거 — BacklogRankService).
-- nullable: 신규 이슈는 rank=NULL(lazy). 드래그(rerank) 또는 rebalance 시 rank 부여.
ALTER TABLE issues ADD COLUMN rank VARCHAR(50);
COMMENT ON COLUMN issues.rank IS 'LexoRank 백로그 정렬 키 (소문자 a-z, 사전순=정렬순, 끝문자!=a). NULL=미부여(lazy). 프로젝트 전역 키 (FR-BL-01).';

-- ── 2. (project_id, rank) 복합 인덱스 ──
-- WHERE project_id=? ORDER BY rank NULLS LAST 백로그 정렬을 인덱스 스캔으로 처리 (NFR4).
-- plain CREATE INDEX (CONCURRENTLY 아님 — V025/V028 형제 일관, Testcontainers 트랜잭션 호환. 현 규모 1K 무해).
-- (project_id, rank) UNIQUE 미강제 — between 동시 충돌 시 tie-break(ORDER BY rank,id)로 해소 (spec 결정 #11).
CREATE INDEX idx_issues_project_rank ON issues (project_id, rank);

-- issues.rank(VARCHAR(50)) LexoRank 정렬 키 컬럼 + 프로젝트별 created_at 순 균등 백필 + NOT NULL + (project_id, rank) 인덱스 — FR-BL-01

-- ── 1. rank 컬럼 추가 (NULL 허용 — 기존 행 백필 전이라 NOT NULL 바로 불가, DATA.md §4 순서) ──
-- rank: 프로젝트 전역 백로그 정렬 키. 소문자 a-z base-26 알파벳 문자열. 사전순 == 백로그 정렬 순서.
-- VARCHAR(50): SDD §13.2.1. 키 길이 한도(고갈 시 rebalance 트리거 — BacklogRankService).
ALTER TABLE issues ADD COLUMN rank VARCHAR(50);
COMMENT ON COLUMN issues.rank IS 'LexoRank 백로그 정렬 키 (소문자 a-z, 사전순=정렬순, 끝문자!=a). 프로젝트 전역 키 (FR-BL-01).';

-- ── 2. 백필 — 프로젝트별 created_at 순으로 3자리 base-26 키를 균등 간격 부여 ──
-- 순수 chr() UPDATE 로는 N>26 다자리 인코딩이 불가하므로 PL/pgSQL DO 블록으로 인라인 처리한다
--   (Flyway Java migration 대신 단일 SQL 자기완결 — 다른 마이그레이션과 동일하게 순수 SQL 유지).
--
-- 인코딩 설계 (끝문자 'a' 회피 = spec FR1).
--   가용 슬롯 = 26(1자리) * 26(2자리) * 25(3자리 b..z) = 16,900 (>1K 충분).
--   i 번째(1-based) 이슈에 slot = i * floor(16900/(N+1)) 부여 (1..16900 단조증가, 균등 간격).
--   slot 디코드: 마지막 자리는 'b'..'z'(25개)로 매핑 → 끝문자 항상 != 'a'.
--   자리값 가중치 (26*25, 25, 1) 가 단조 → 디코드 문자열도 사전순 단조증가 (created_at 순 == rank 순 보존).
DO $$
DECLARE
    -- 마지막 자리 가용 문자 수 (b..z = 25개, 'a' 제외).
    last_digit_count CONSTANT INT := 25;
    -- 전체 가용 슬롯 수 = 26 * 26 * 25.
    slot_space CONSTANT INT := 26 * 26 * last_digit_count;
    proj          RECORD;
    issue_row     RECORD;
    issue_count   INT;
    unit          INT;
    seq           INT;
    slot          INT;
    rem           INT;
    c1            INT;  -- 첫째 자리 (a..z)
    c2            INT;  -- 둘째 자리 (a..z)
    c3            INT;  -- 마지막 자리 (b..z — 'a' 회피)
    rank_key      TEXT;
BEGIN
    -- 프로젝트 단위로 백필 (rank 는 프로젝트 전역 정렬 키 — spec 결정 #9).
    FOR proj IN SELECT id FROM projects LOOP
        SELECT COUNT(*) INTO issue_count FROM issues WHERE project_id = proj.id;
        CONTINUE WHEN issue_count = 0;

        -- 균등 간격 단위. (N+1) 로 나눠 양끝 여유 확보. 최소 1 보장 (slot 단조 유지).
        unit := GREATEST(slot_space / (issue_count + 1), 1);

        seq := 0;
        -- created_at, id 순(결정적 tie-break) 으로 1..N 슬롯을 균등 부여.
        FOR issue_row IN
            SELECT id FROM issues WHERE project_id = proj.id ORDER BY created_at, id
        LOOP
            seq := seq + 1;
            slot := seq * unit;

            -- slot 디코드 → 3자리 base-26 키 (마지막 자리만 b..z).
            c3 := slot % last_digit_count;                 -- 0..24 → 'b'..'z'
            rem := slot / last_digit_count;
            c2 := rem % 26;                                -- 0..25 → 'a'..'z'
            c1 := (rem / 26) % 26;                          -- 0..25 → 'a'..'z'

            rank_key := chr(ascii('a') + c1)
                     || chr(ascii('a') + c2)
                     || chr(ascii('b') + c3);

            UPDATE issues SET rank = rank_key WHERE id = issue_row.id;
        END LOOP;
    END LOOP;
END $$;

-- ── 3. 백필 완료 후 NOT NULL 제약 적용 (DATA.md §4) ──
ALTER TABLE issues ALTER COLUMN rank SET NOT NULL;

-- ── 4. (project_id, rank) 복합 인덱스 ──
-- WHERE project_id=? ORDER BY rank 백로그 정렬을 인덱스 스캔으로 처리 (NFR4).
-- plain CREATE INDEX (CONCURRENTLY 아님 — V025/V028 형제 일관, Testcontainers 트랜잭션 호환. 현 규모 1K 무해).
-- (project_id, rank) UNIQUE 미강제 — between 동시 충돌 시 tie-break(ORDER BY rank,id)로 해소 (spec 결정 #11).
CREATE INDEX idx_issues_project_rank ON issues (project_id, rank);

-- 보드 컬럼이 워크플로우 상태를 여러 개 매핑한다 — 연결 테이블 신설 + 백필 (컬럼:상태 1:1 → 1:N)
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).
--
-- 설계 정본. docs/specs/2026-09-03-board-column-multi-state.md R1·R2·N1·E1·E6 · 편차 X1 /
-- docs/adr/2026-09-03-board-column-multi-state.md D1(연결 테이블) · D3(한 상태는 한 컬럼에만).
--
-- 왜 필요한가. `V500:26` 이 「각 행이 워크플로우 상태 1개에 1:1 매핑된다」로 컬럼 모델을 못박았고
-- `UNIQUE (board_id, state_key)` 가 그것을 강제한다. 그래서 지라의 흔한 구성인 「진행 중 컬럼에
-- in_progress + in_review 를 함께」를 만들 수 없고 컬럼이 상태 수만큼 늘어난다.
-- 그 1:1 은 지라와 대조된 적이 없다 — 원 스펙(2026-06-20)에는 `## Jira 대조` 절이 아예 없다.
--
-- ★ 서식 정본은 `V203__add_global_status_catalog.sql:36-60` 의 `workflow_statuses` 다.
--   같은 저장소 안의 같은 형태 N:M 연결 테이블(부모 FK CASCADE · 대상 키 · display_order ·
--   UNIQUE(부모, 대상) · FK 인덱스 명시)이라 발명하지 않고 복제한다.
--
-- ★★ 되돌리기 — 이 마이그레이션은 **조건부로** 되돌릴 수 있다. 파괴적 변경은 0 이지만
--   「무조건 되돌릴 수 있다」는 거짓이다(plan 리뷰 CONCERN-3).
--
--     DROP TABLE board_column_states;
--     ALTER TABLE board_columns DROP CONSTRAINT uq_board_columns_id_board;
--     ALTER TABLE board_columns ALTER COLUMN state_key SET NOT NULL;   -- ← 여기가 조건부다
--
--   마지막 줄은 `state_key` 가 NULL 인 행이 하나라도 있으면 **실패한다.** 이 PR 이 상태 0개
--   컬럼을 허용하고(스펙 E1·N4) 컬럼 생성 API 가 실제로 그런 컬럼을 만들기 때문이다.
--   **되돌리기 전에 반드시 아래 두 줄로 확인한다**(plan 리뷰 CONCERN-1).
--
--     -- ① 0 이어야 한다
--     SELECT count(*) FROM board_columns WHERE state_key IS NULL;
--     -- ② 0 이 아니면 이 컬럼들을 먼저 지우거나 상태를 매핑한다
--     SELECT id, board_id, name FROM board_columns WHERE state_key IS NULL;
--
-- ★ `board_columns.state_key` 를 **DROP 하지 않는다.** `DATA.md §4` 규칙 1 의 3단 분할이다 —
--   ①신설+백필(여기) ②코드 전환(같은 PR) ③DROP(부채 178). 한 PR 에서 신설과 DROP 을 함께 하면
--   롤백 창이 사라진다. 그동안 쓰기 경로가 두 곳을 채우고, 읽기는 신규 테이블만 본다.

-- ── ① board_column_states — 컬럼 ↔ 상태 키 1:N ─────────────────────────────────
-- 각 행이 「이 컬럼이 이 상태를 담는다」를 뜻한다. 컬럼 하나가 0개 이상을 가진다.
--
-- ★★ board_id 비정규화가 편차 X1(한 상태는 한 컬럼에만)의 실질이다.
--   column_id 만 두면 「한 상태가 같은 보드의 두 컬럼에」를 DB 가 못 막는다.
--   `V500:39` 의 UNIQUE (board_id, state_key) 가 지키던 불변식을 **그대로 옮기는** 것이다.
--
-- ★★★ 그런데 그 비정규화 자체가 갈릴 수 있다(plan 리뷰 ceo-4).
--   FK 두 개(column_id→board_columns · board_id→boards)만으로는 **둘 사이의 정합을 아무도
--   안 지킨다** — 애플리케이션이 컬럼의 실제 소유 보드와 다른 board_id 를 쓰면
--   UNIQUE(board_id, state_key) 가 엉뚱한 것을 지키고 X1 이 조용히 무너진다.
--   그래서 **복합 FK** 로 DB 가 지게 한다. 저장소 복합 FK 선례는 0건이고 이것이 첫 사례이나,
--   `DATA.md §1` 의 「DB 가 지킬 수 있는 불변식은 DB 가 진다」에 정면으로 맞는다.
--   복합 FK 의 참조 대상에는 UNIQUE 가 필요하므로 board_columns 에 (id, board_id) 를 먼저 건다.

-- ★ 멱등(E6). PostgreSQL 은 `ADD CONSTRAINT ... IF NOT EXISTS` 를 지원하지 않는다 —
--   `CREATE TABLE`·`CREATE INDEX` 와 달리 이 한 줄만 재실행에서 죽는다(테스트가 실측으로 잡았다).
--   `pg_constraint` 를 직접 보고 없을 때만 건다.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_board_columns_id_board'
    ) THEN
        ALTER TABLE board_columns
            ADD CONSTRAINT uq_board_columns_id_board UNIQUE (id, board_id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS board_column_states (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    column_id     UUID        NOT NULL,
    board_id      UUID        NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    state_key     VARCHAR(50) NOT NULL,       -- 매핑된 워크플로우 상태 키(project-workflow statuses.key)
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- 편차 X1. 한 보드 안에서 같은 상태가 두 컬럼에 가지 않는다. V500:39 를 옮긴 것이다.
    UNIQUE (board_id, state_key),
    -- 같은 컬럼에 같은 상태를 두 번 담지 않는다.
    UNIQUE (column_id, state_key),
    -- ★ceo-4. column_id 와 board_id 의 정합을 DB 가 진다.
    FOREIGN KEY (column_id, board_id) REFERENCES board_columns (id, board_id) ON DELETE CASCADE
);

COMMENT ON TABLE  board_column_states               IS '보드 컬럼이 담는 워크플로우 상태 목록 — 컬럼 1개에 상태 0개 이상 (지라 드롭존 대응)';
COMMENT ON COLUMN board_column_states.column_id     IS '소유 컬럼. board_id 와 함께 복합 FK 로 board_columns(id, board_id) 를 참조한다';
COMMENT ON COLUMN board_column_states.board_id      IS '소유 보드(비정규화). UNIQUE(board_id, state_key) 로 「한 상태는 한 컬럼에만」을 강제하는 데 쓴다';
COMMENT ON COLUMN board_column_states.state_key     IS '매핑된 워크플로우 상태 키. FK 없이 문자열 참조 (BC 격리 · statuses.key 는 불변)';
COMMENT ON COLUMN board_column_states.display_order IS '컬럼 **안**의 드롭존 순서(작을수록 위). 컬럼끼리의 순서는 board_columns.display_order 가 진다';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다).
-- board_id 는 UNIQUE (board_id, state_key) 의 leftmost prefix 가 덮으므로 따로 두지 않는다.
-- `V500:36-38` 이 같은 판단을 적었다 — 중복 인덱스 회피.
CREATE INDEX IF NOT EXISTS idx_board_column_states_column
    ON board_column_states (column_id);

-- ── ② 백필 — 기존 컬럼당 상태 1개 (R2 · N1) ────────────────────────────────────
-- 마이그레이션 전후로 컬럼 구성과 카드 배치가 **완전히 같아야** 한다. 1:N 은 스키마가 허용할
-- 뿐이고, 이 시점의 데이터는 전부 1:1 이다.
--
-- ★ 멱등(E6). `ON CONFLICT DO NOTHING` 으로 재실행에서 행이 늘지 않는다.
--   부채 161 이 「V506 을 되돌렸다 재적용하면 중복 생성된다」를 지적했다 — 반복하지 않는다.
--   Flyway 는 같은 버전을 두 번 적용하지 않으므로 이 성질은 Flyway 밖에서만 잴 수 있다
--   (BoardColumnStatesMigrationTest 가 SQL 원문을 JDBC 로 직접 재실행한다).
INSERT INTO board_column_states (column_id, board_id, state_key, display_order)
SELECT c.id, c.board_id, c.state_key, 0
FROM board_columns c
WHERE c.state_key IS NOT NULL
ON CONFLICT (board_id, state_key) DO NOTHING;

-- ── ③ 레거시 컬럼의 NOT NULL 완화 (gap G1) ────────────────────────────────────
-- 상태 0개 컬럼(스펙 E1·N4)을 이중 기록 창에서 표현하려면 필수다. 이것이 없으면 컬럼 생성 API 가
-- 상태를 반드시 요구하게 되고, 「빈 컬럼을 만들고 상태를 옮겨 넣는」 지라의 조작 순서(J2)를
-- 따를 수 없다.
--
-- ★ 제약 **완화**라 기존 행은 무영향이고 데이터 파괴가 없다. 다만 되돌리기가 조건부가 된다 —
--   머리 주석의 확인 쿼리 참조.
ALTER TABLE board_columns
    ALTER COLUMN state_key DROP NOT NULL;

COMMENT ON COLUMN board_columns.state_key IS '레거시 단일 매핑(이중 기록 대상 · 정본은 board_column_states). 상태 0개 컬럼이면 NULL. DROP 은 부채 178';

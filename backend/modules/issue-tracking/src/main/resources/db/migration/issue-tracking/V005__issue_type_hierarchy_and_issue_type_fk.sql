-- issue-tracking V005 — issue_types.hierarchy_level 컬럼 추가 + issues.type_id FK + 부분 unique 인덱스 교체

-- ============================================================
-- 1. issue_types.hierarchy_level 컬럼 추가
-- ============================================================
-- hierarchy_level: 이슈 유형 계층 깊이.
--   epic=1 (최상위), task/story/bug=0 (기본), subtask=-1 (하위)
-- DEFAULT 0 으로 기존 row(5 표준 타입)가 자동으로 0을 가짐.
-- 이후 UPDATE로 epic=1, subtask=-1 로 교정.
ALTER TABLE issue_types
    ADD COLUMN hierarchy_level INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN issue_types.hierarchy_level IS
    '이슈 유형 계층 깊이. epic=1(최상위), task/story/bug=0(기본), subtask=-1(하위 작업).';

-- epic: 하위 이슈를 묶는 최상위 컨테이너 → level 1
UPDATE issue_types
   SET hierarchy_level = 1
 WHERE key = 'epic';

-- subtask: 다른 이슈의 하위 작업 → level -1
UPDATE issue_types
   SET hierarchy_level = -1
 WHERE key = 'subtask';

-- story / task / bug 는 DEFAULT 0 유지 — 별도 UPDATE 불필요.

-- ============================================================
-- 2. (B1) key UNIQUE 제약 교체 — 전체 unique → 부분 unique
-- ============================================================
-- V003 에서 `key VARCHAR(30) NOT NULL UNIQUE` 로 선언된 제약은
-- PostgreSQL 이 자동 부여한 이름 issue_types_key_key 로 존재한다.
-- 소프트 삭제 후 같은 key 를 재활성화(재INSERT)할 수 있도록
-- 전체 unique 제약을 DROP 하고 deleted_at IS NULL 조건의 부분 unique 로 교체한다.
-- 이유: deleted_at NOT NULL(삭제) row 는 unique 검사 대상에서 제외되어야
--       같은 key 가 여러 역사적 row 를 가질 수 있다 (이름 재사용 허용).
ALTER TABLE issue_types
    DROP CONSTRAINT IF EXISTS issue_types_key_key;

-- 부분 unique 인덱스 생성 (활성 row 에만 적용)
-- 이름 ux_<table>_<column>_<modifier>: ux 접두사로 unique 임을 명시.
CREATE UNIQUE INDEX ux_issue_types_key_active
    ON issue_types (key)
    WHERE deleted_at IS NULL;

-- V003 에서 생성된 비고유 부분 인덱스는 ux_issue_types_key_active 로 대체되므로 제거.
-- (같은 컬럼의 중복 인덱스는 플래너 혼란 + 쓰기 오버헤드)
DROP INDEX IF EXISTS ix_issue_types_key_active;

-- ============================================================
-- 3. issues.type_id 컬럼 추가 + backfill + NOT NULL + FK
-- ============================================================

-- 3-1. NULL 허용으로 먼저 추가 (기존 row 때문에 NOT NULL 바로 불가)
ALTER TABLE issues
    ADD COLUMN type_id BIGINT;

COMMENT ON COLUMN issues.type_id IS
    'issue_types.id FK. 이슈 유형 식별자. NOT NULL — 이슈는 반드시 유형을 가진다.';

-- 3-2. 기존 row backfill — task 타입으로 채운다.
-- 사유: V005 이전에는 type_id 컬럼이 없었으므로 모든 기존 이슈는
--       "기본 이슈(task)" 로 간주한다. task 는 is_standard=true 이며
--       hierarchy_level=0 인 가장 범용적인 유형이다.
-- deleted_at IS NULL 조건: 활성 task 타입 row 만 선택 (V005 이후 soft-delete 방어).
UPDATE issues
   SET type_id = (
       SELECT id
         FROM issue_types
        WHERE key = 'task'
          AND deleted_at IS NULL
        LIMIT 1
   )
 WHERE type_id IS NULL;

-- 3-3. backfill 완료 후 NOT NULL 제약 적용
ALTER TABLE issues
    ALTER COLUMN type_id SET NOT NULL;

-- 3-4. FK 제약 — issues.type_id → issue_types.id
-- 이름 fk_<table>_<column> 패턴 (DEVELOPMENT.md 명명 규칙).
ALTER TABLE issues
    ADD CONSTRAINT fk_issues_type_id
        FOREIGN KEY (type_id)
        REFERENCES issue_types (id);

-- 3-5. FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함)
-- ix_<table>_<column> 패턴.
CREATE INDEX ix_issues_type_id
    ON issues (type_id);

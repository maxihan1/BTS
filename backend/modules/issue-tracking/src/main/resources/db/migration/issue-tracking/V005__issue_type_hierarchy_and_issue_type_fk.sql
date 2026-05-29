-- issue-tracking V005 — issue_types.hierarchy_level 컬럼 추가 + issues.type_id FK + 부분 unique 인덱스 교체
--
-- 이 마이그레이션이 해결하는 두 문제.
--   1. FR-IS-02 요구: IssueType 에 계층 깊이(hierarchyLevel) 도입.
--      epic(최상위)/task·story·bug(기본)/subtask(하위) 를 숫자로 구분 → 이슈 생성·전이 검증에 활용.
--   2. B1 BLOCKER: V003 의 전체 UNIQUE 제약(issue_types_key_key) 은 소프트 삭제 후 key 재사용을
--      막는다. 부분 unique 인덱스(ux_issue_types_key_active, deleted_at IS NULL) 로 교체하여
--      논리 삭제 후 같은 key 의 새 row 를 허용한다. 중복된 비고유 부분 인덱스(ix_issue_types_key_active)
--      는 쓰기 오버헤드·플래너 혼란을 일으키므로 함께 제거한다.

-- ============================================================
-- 1. issue_types.hierarchy_level 컬럼 추가
-- ============================================================
-- DEFAULT 0: 기존 5종 표준 row 가 자동으로 0(기본 레벨)을 갖도록 backfill 없이 추가.
-- epic/subtask 는 아래 UPDATE 로 교정한다.
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
-- 소프트 삭제 후 같은 key 로 새 IssueType 을 재등록(재INSERT)할 수 있도록
-- 전체 unique 제약을 DROP 하고 deleted_at IS NULL 조건의 부분 unique 로 교체한다.
ALTER TABLE issue_types
    DROP CONSTRAINT IF EXISTS issue_types_key_key;

-- 부분 unique 인덱스: 활성(deleted_at IS NULL) row 에만 key unique 강제.
-- ux_ 접두사 = unique index 명명 규칙.
CREATE UNIQUE INDEX ux_issue_types_key_active
    ON issue_types (key)
    WHERE deleted_at IS NULL;

-- V003 에서 생성된 비고유 부분 인덱스(ix_issue_types_key_active)는 위 unique 인덱스로 대체.
-- 같은 컬럼에 두 인덱스가 공존하면 플래너 혼란 + 쓰기 오버헤드가 증가하므로 제거한다.
DROP INDEX IF EXISTS ix_issue_types_key_active;

-- ============================================================
-- 3. issues.type_id 컬럼 추가 + backfill + NOT NULL + FK
-- ============================================================
-- NOT NULL 을 바로 추가할 수 없는 이유: 이미 존재하는 row 가 type_id 를 모르기 때문.
-- 패턴: NULL 허용으로 추가 → backfill → NOT NULL 제약 추가 (DATA.md §6 순서).

-- 3-1. NULL 허용으로 먼저 추가 (기존 row 때문에 NOT NULL 바로 불가)
ALTER TABLE issues
    ADD COLUMN type_id BIGINT;

COMMENT ON COLUMN issues.type_id IS
    'issue_types.id FK. 이슈 유형 식별자. NOT NULL — 이슈는 반드시 유형을 가진다.';

-- 3-2. 기존 row backfill — task 타입으로 채운다.
-- 사유: V005 이전에는 type_id 컬럼이 없었으므로 모든 기존 이슈는
--       "기본 이슈(task)" 로 간주한다. task 는 is_standard=true 이며
--       hierarchy_level=0 인 가장 범용적인 유형이다.
-- deleted_at IS NULL 조건: 활성 task 타입 row 만 선택 (소프트 삭제 방어).
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
-- fk_<table>_<column> 명명 규칙 (DEVELOPMENT.md).
ALTER TABLE issues
    ADD CONSTRAINT fk_issues_type_id
        FOREIGN KEY (type_id)
        REFERENCES issue_types (id);

-- 3-5. FK 인덱스 (PostgreSQL 은 FK 에 인덱스 자동 생성 안 함 — DATA.md §5).
-- ix_<table>_<column> 명명 규칙.
CREATE INDEX ix_issues_type_id
    ON issues (type_id);

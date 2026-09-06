-- 보드 설정 값 CHECK — working_days 원소 집합과 board_timezone 을 DB 가 마지막 방어선으로 막는다 (부채 177 · 리뷰 C4)

-- V509 는 `time_tracking` 에 CHECK 를 건 근거를 스스로 이렇게 적었다.
--   「사전 검사는 사용자에게 이유를 주려고 있는 것이고 **마지막 방어선은 DB 다**」
-- 그런데 같은 절이 낸 `working_days` · `board_timezone` 에는 제약을 걸지 않았고,
-- `WorkingDaysSettingsService` 가 그 사실을 인정한다 — 「이 목록이 유일한 방어선이다」.
--
-- 읽는 쪽이 그 단일 방어선을 신뢰한다.
--   · `SprintBurndownService` 의 `mapNotNull(WEEKDAY_BY_KEY::get)` 이 해석 불가 요일을 **조용히 버린다**
--     → 번다운 x축이 소리 없이 좁아진다.
--   · 같은 파일의 `ZoneId.of(timezone)` 이 무방비다 → 비-IANA 문자열 한 행이면 그 보드의
--     번다운 API 가 `DateTimeException` 으로 **영구 500** 이다.
--
-- ★기존 데이터 안전성. CHECK 를 거는 `ALTER TABLE` 은 **기존 행을 전부 검사**하고 위반이 하나라도
--   있으면 마이그레이션이 죽는다. 이 두 칸은 **V509 가 방금 만들었고 V509 는 아직 릴리스되지 않았다**
--   (부채 177 과 같은 PR 로 나간다). 그래서 프로덕션에 위반 행이 존재할 수 없어 `NOT VALID` +
--   별도 백필을 쓰지 않는다. V509 가 릴리스된 뒤였다면 그 경로를 택했어야 한다.
--
-- ★재실행 안전. `pg_constraint` 를 먼저 본다 — 조상은 V508:54-62 이고 V509:88-99 가 그것을
--   `conrelid` 로 보강했다(V505 는 `ADD COLUMN ... CHECK` 인라인이라 이 관용구가 아니다).
--
-- ★★`conrelid = 'boards'::regclass` 를 함께 본다. **제약 이름은 스키마가 아니라 테이블 단위로
--   유일하므로** `conname` 만 보면 다른 테이블의 동명 제약에 속아 CHECK 를 조용히 건너뛴다 —
--   V509 가 같은 자리에 그 이유를 적어 두었고 이 파일은 그것을 따른다.

-- ① 표준 근무일 — 원소가 3글자 요일 7종 중 하나여야 하고 NULL 원소를 허용하지 않는다.
--    칸 자체의 NULL(= 미설정)은 그대로 허용한다 — 「미설정」과 「근무일 0개」의 구분은
--    이 PR 이 전 층에서 지켜 온 계약이고 CHECK 가 그것을 깨면 안 된다.
--    `<@` 는 왼쪽이 오른쪽의 부분집합인지 본다. NULL 원소가 있으면 결과가 NULL 이라
--    CHECK 가 통과해 버리므로 `array_position(working_days, NULL) IS NULL` 로 따로 막는다.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'boards_working_days_allowed'
          AND conrelid = 'boards'::regclass
    ) THEN
        ALTER TABLE boards
            ADD CONSTRAINT boards_working_days_allowed CHECK (
                working_days IS NULL
                OR (
                    array_position(working_days, NULL) IS NULL
                    AND working_days <@ ARRAY['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']::varchar[]
                )
            );
    END IF;
END $$;

-- ② 보드 타임존 — PostgreSQL 자신의 tz 데이터베이스로 판정한다.
--    IANA 목록을 CHECK 에 나열할 수 없고(부분 목록은 곧 썩는다) `pg_timezone_names` 는
--    서브쿼리라 CHECK 에 못 쓴다. `timezone(text, timestamptz)` 는 IMMUTABLE 이라 쓸 수 있고,
--    모르는 이름이면 예외를 던진다.
--
--    ★**이 CHECK 가 보는 집합은 Java `ZoneId` 집합과 다르다.** POSIX 표기(`ABC5` 등)는
--    PostgreSQL 을 통과하고 `ZoneId.of` 에서 죽는다. 그러니 **읽는 쪽 폴백은 여전히 필요하다** —
--    이 제약이 그 구멍을 닫았다고 읽지 마라. `BoardSettingsValueCheckMigrationTest` 의 ⑤ 축이
--    그 차집합을 단언으로 고정하고 있고, 그 테스트가 red 가 되면 이 문단을 다시 써야 한다.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'boards_board_timezone_allowed'
          AND conrelid = 'boards'::regclass
    ) THEN
        ALTER TABLE boards
            ADD CONSTRAINT boards_board_timezone_allowed CHECK (
                board_timezone IS NULL
                OR timezone(board_timezone, TIMESTAMPTZ '2000-01-01 00:00:00+00') IS NOT NULL
            );
    END IF;
END $$;

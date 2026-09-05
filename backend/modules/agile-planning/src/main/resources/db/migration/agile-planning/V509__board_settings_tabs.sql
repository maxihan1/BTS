-- 보드 설정 잔여 4탭의 저장 칸 — boards 설정 3칸(추정·작업일) + 신설 테이블 3개(비근무일·카드 레이아웃·상세 보기) (부채 177)
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).
--
-- 설계 정본. docs/specs/2026-09-05-board-settings-remaining-tabs-177.md §데이터 모델 · R5·R6 ·
-- docs/plans/2026-09-05-board-settings-remaining-tabs-177.md Task 1·2·3·4.
-- 지라 근거. J36(시간 추적 2종) · J38(표준 근무일) · J39(비근무일) · J40(타임존) ·
--          J17(카드 필드 최대 3개) · J18(보드/백로그 뷰별 구성) · J46·J47·J48(상세 보기 필드).
--
-- 왜 한 V번호인가. 네 탭이 각자 마이그레이션을 내면 V번호 동시 브랜치 충돌을 네 번 상대한다.
-- 한 파일에 몰아 한 번만 상대하는 것이 4탭을 한 PR 로 묶는 유일한 기술적 이득이다(plan §착수 시점).
--
-- ★★ 이 파일의 핵심 판단은 「무엇을 NOT NULL 로 두지 **않았는가**」다. 아래 ① 참조.
--
-- ★★ 되돌리기 — **무조건** 완전 원복된다. 데이터 손실은 설정값뿐이다.
--   ★이 목록은 ①②③④ **네 절 전부**를 센다. DROP TABLE 은 둘이 아니라 **셋**이다.
--
--     DROP TABLE board_non_working_dates;   -- ②
--     DROP TABLE board_card_layout_fields;  -- ③
--     DROP TABLE board_detail_view_fields;  -- ④
--     ALTER TABLE boards
--         DROP COLUMN time_tracking,        -- ① (boards_time_tracking_allowed 도 함께 사라진다)
--         DROP COLUMN working_days,
--         DROP COLUMN board_timezone;
--
--   V508 은 되돌리기가 **조건부**였다(state_key 를 NOT NULL 로 되돌리는 줄이 NULL 행 하나에도
--   실패한다). 여기는 그렇지 않다 — 제약을 다시 조이는 줄이 없고, 신설한 칸·테이블에 의존하는
--   기존 객체도 없어 순서와 데이터에 관계없이 성공한다. 「무조건」이라고 쓸 수 있는 근거가 이것이다.
--   되돌리면 4탭의 설정값이 사라지고 보드는 마이그레이션 이전 동작으로 정확히 돌아간다 —
--   working_days 가 NULL 이었으므로 번다운은 애초에 달력일 전부를 돌고 있었다(①의 ★★).
--
--   ★ 이 머리말만 읽고 DROP 을 옮겨 적는 사람이 있다. 한때 이 목록은 ①②③ 만 세고 있었고
--   ④ board_detail_view_fields 가 뒤이어 붙은 뒤에도 그대로였다 — 그러면 되돌린 뒤에도
--   테이블 하나가 남는다. 절을 더할 때 이 목록도 함께 세는 것이 규율이다.
--   산문은 기계가 안 읽는다 — 되돌리기를 실제로 실행해 스키마가 적용 전과 같은지 재는 것은
--   BoardSettingsIdempotencyTest 다(plan Task 4 REFACTOR · 부채 161).
--
-- ★★ 재실행 안전. 이 파일은 손으로 다시 태워도 죽지 않는다 — ADD COLUMN·CREATE TABLE 은
--   IF NOT EXISTS 를 달고, 문법이 없는 ADD CONSTRAINT 는 pg_constraint 를 보는 DO 블록으로
--   감쌌다(①). 같은 판정을 BoardSettingsIdempotencyTest 가 「1차 재실행 후 ↔ 2차 재실행 후」로 잰다.

-- ── ① boards 설정 3칸 — 추정 탭(J36) · 작업일 탭(J38·J40) ──────────────────────
--
-- ★ time_tracking 만 NOT NULL DEFAULT 다. 그 기본값이 **현행 동작 그 자체**라서 백필이
--   관측 가능한 변화를 만들지 않는다 — 지금 어떤 보드도 시간 추적으로 진행을 재지 않는다.
--   V505 의 board_type DEFAULT 'KANBAN' 이 같은 판단을 적었다(기존 보드 전량 무변경 보존).
--
-- ★★ working_days 와 board_timezone 은 **NULL 허용이 설계다.** 실수로 빠진 NOT NULL 이 아니다.
--   NULL = 「미설정 = 달력일 전부 = 현행 유지」이고, 이것이 스펙 R6 그 자체다.
--   NOT NULL DEFAULT '{MON,TUE,WED,THU,FRI}' 로 두면 **기존 모든 스프린트의 번다운이
--   배포 순간 바뀐다** — BurndownCalculator 가 x축을 주말 4일만큼 좁히고 ideal 선의 분모도
--   함께 줄어드는데, 아무도 설정을 바꾸지 않았는데 차트가 달라진다.
--   차트가 조용히 바뀌는 것은 되돌려도 「원래 어땠는지」를 아무도 모르는 종류의 사고다.
--   BoardSettingsMigrationTest 가 이 두 칸의 기본값 부재를 단언해 재발을 막는다.
--
-- ★★ time_tracking 에 CHECK 를 **건다**(아래 boards_time_tracking_allowed). 초판은 걸지 않았고
--   그 근거로 「스펙 §데이터 모델이 CHECK 없이 선언했다」를 댔다. 사실 관계는 맞다. 그러나 그것은
--   **스펙이 옳다는 근거가 아니라 스펙이 형제와 어긋난다는 증거**다 — 같은 절이
--   board_card_layout_fields · board_detail_view_fields 에는 CHECK 를 명시했다는 사실은
--   「구분」으로도 「셋 중 하나만 빠졌다」로도 똑같이 읽힌다. 스펙 문면은 어느 쪽도 가르지 못한다.
--
--   그래서 판정 근거를 스펙이 아니라 **형제 칸**에서 찾았다. 같은 boards 테이블의
--   board_type(V505:11) · swimlane_field(V501:18) 이 같은 모양의 닫힌 열거형을
--   <테이블>_<칸>_allowed CHECK 로 지킨다. 같은 테이블의 같은 종류 칸 셋 중 하나만 다른
--   규율을 받을 이유가 없다 — 규율이 갈리는 자리가 곧 다음 사람이 헷갈리는 자리다.
--
--   EstimationSettingsService(Task 9)의 검증은 이 제약을 **대신하지 않는다.** 사전 검사는
--   사용자에게 400 의 이유를 주려고 있는 것이고 마지막 방어선은 DB 다(③ 의 #444 X1 과 같은 판단).
--   값 종류가 늘면 마이그레이션이 따라붙는다 — V502 가 swimlane_field 에 EPIC 을 더하며
--   DROP CONSTRAINT → ADD CONSTRAINT 한 쌍으로 그 비용을 이미 치른 선례다. 뒤집으려면
--   스펙과 이 CHECK 를 함께 고친다.

-- ★ IF NOT EXISTS — 이 파일을 손으로 다시 태우는 자리(복구·재해 훈련·부분 적용 뒤 재실행)에서
--   42701 로 죽으면 **뒤따르는 문 전부가 적용되지 않는다.** 기계 판정은
--   BoardSettingsIdempotencyTest 다(plan Task 4 · 부채 161 · #444 가 V506 에서 밟은 자리).
ALTER TABLE boards
    ADD COLUMN IF NOT EXISTS time_tracking  VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS working_days   VARCHAR(3)[] NULL,
    ADD COLUMN IF NOT EXISTS board_timezone VARCHAR(64)  NULL;

-- 형제 board_type · swimlane_field 와 같은 <테이블>_<칸>_allowed 관용구다(위 ★★ 가 근거).
-- 인라인이 아니라 별도 문인 것은 의도다 — V509 의 유일한 ADD CONSTRAINT 이고,
-- 「ADD CONSTRAINT 에는 IF NOT EXISTS 가 없다」는 멱등 요구가 감쌀 실제 대상이 된다(plan Task 4).
--
-- ★★ 그래서 DO 블록이다. ADD COLUMN·CREATE TABLE 과 달리 ADD CONSTRAINT 에는 IF NOT EXISTS
--   **문법 자체가 없어** 재실행이 42710(duplicate_object)으로 죽는다. pg_constraint 를 먼저 보고
--   없을 때만 거는 것이 표준 처방이다(부채 161 · #444 가 같은 자리에서 밟았다).
--
-- ★ conrelid 로 테이블까지 좁힌다. 제약 이름은 스키마가 아니라 **테이블 단위**로 유일하므로
--   conname 만 보면 다른 테이블의 동명 제약에 속아 CHECK 를 조용히 건너뛴다.
--
-- ★ 이름 boards_time_tracking_allowed 는 계약이다. 바꾸면 이 블록이 찾지 못해 재실행마다
--   제약을 다시 걸려 하고, BoardSettingsMigrationTest 의 이름 단언도 함께 red 가 된다.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'boards_time_tracking_allowed'
          AND conrelid = 'boards'::regclass
    ) THEN
        ALTER TABLE boards
            ADD CONSTRAINT boards_time_tracking_allowed CHECK (time_tracking IN ('NONE', 'REMAINING_AND_SPENT'));
    END IF;
END
$$;

COMMENT ON COLUMN boards.time_tracking IS
    'NONE / REMAINING_AND_SPENT — 진행을 무엇으로 재는가(J36). 기본 NONE 이 현행 동작이라 백필이 무변경이다. 칸반에서는 읽는 쪽이 board_type 을 보고 무시한다(스펙 E6 — 종류를 왕복시켜도 값을 지우지 않는다).';
COMMENT ON COLUMN boards.working_days IS
    '표준 근무일 요일 집합(MON..SUN · J38). ★NULL = 미설정 = 달력일 전부(현행 유지, 스펙 R6). 기본값을 월~금으로 채우면 기존 모든 스프린트의 번다운이 배포 순간 바뀌므로 NOT NULL DEFAULT 로 바꾸지 말 것.';
COMMENT ON COLUMN boards.board_timezone IS
    'IANA 타임존(J40). ★NULL = 미설정 = UTC(현행 유지). worklog 일 귀속 기준이며 설정되면 SprintBurndownService 가 이 타임존으로 날짜를 묶는다(스펙 R10).';

-- ── ② board_non_working_dates — 공휴일·일회성 휴무일 (J39) ─────────────────────
--
-- 왜 boards 의 배열이 아니라 별도 테이블인가. 날짜는 개수 상한이 없고 개별 추가/삭제가
-- 기본 조작이다(J39 원문 — "select a date using the date picker ... then select Add date").
-- 배열 한 칸이면 한 날짜를 지우려고 전체를 읽어 다시 쓰게 되어 연속 조작이 lost update 를 낸다.
-- 반면 working_days 는 요일 7개로 상한이 닫혀 있고 항상 통째로 저장되므로 배열이 맞다.
--
-- ★ deleted_at 이 없다. 이 테이블은 엔티티가 아니라 보드가 소유한 **설정 값 목록**이고,
--   비근무일 해제는 「삭제 이력을 남길 사건」이 아니라 설정 되돌리기다.
--   같은 성격의 board_column_states(V508)도 deleted_at 을 두지 않았다.
--
-- ★ 스프린트 기간 **밖**의 날짜도 저장한다(스펙 E8). 기간을 아는 것은 계산 시점이지 저장 시점이
--   아니므로 DB 가 막으면 다음 스프린트의 휴일을 미리 등록할 수 없게 된다.
CREATE TABLE IF NOT EXISTS board_non_working_dates (
    board_id UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    date     DATE NOT NULL,
    -- 복합 PK 가 「같은 보드에 같은 날짜 두 번」을 막는다. 그리고 이 PK 인덱스의 leftmost
    -- prefix(board_id)가 FK 조회·CASCADE 점검을 덮으므로 board_id 전용 인덱스를 따로 두지
    -- 않는다 — DATA.md §7 의 의도를 충족하면서 중복 인덱스를 피한다(V500:36-38 과 같은 판단).
    PRIMARY KEY (board_id, date)
);

COMMENT ON TABLE  board_non_working_dates          IS '보드별 비근무일 — 공휴일·일회성 휴무(J39). 번다운 x축에서 제외된다. 비어 있으면 표준 근무일만 적용된다';
COMMENT ON COLUMN board_non_working_dates.board_id IS '소유 보드. 보드가 삭제되면 함께 사라진다(ON DELETE CASCADE — 고아 행을 남기지 않는다)';
COMMENT ON COLUMN board_non_working_dates.date     IS '쉬는 날(보드 타임존 기준 달력일). 스프린트 기간 밖이어도 저장되며 계산에서 자연히 무시된다(스펙 E8)';

-- ── ③ board_card_layout_fields — 카드 레이아웃 탭 (J17·J18) ────────────────────
--
-- ★★ 상한 3(J17)을 서비스가 아니라 **DB CHECK 로** 박은 이유. 서비스 검증만 두면 두 관리자가
--   동시에 저장할 때 각자 「지금 0개니까 3개 넣어도 된다」를 통과해 6개가 들어간다.
--   #444 가 X1 경합에서 이미 이름 붙인 양식이고, 그때 쓴 문장이 이것이다 —
--   **사전 검사는 사용자에게 이유를 주려고 있는 것이지 DB 제약을 대신하지 않는다.**
--   400 을 내는 쪽(카드 레이아웃 서비스)과 이 CHECK 는 둘 중 하나를 지워도 다른 하나가
--   여전히 막는 관계여야 한다. 스펙 완료 기준 2 가 그 대조군을 요구한다.
--
-- ★ 상한을 「행 개수」가 아니라 **position 값의 범위**로 표현했다. 개수 제약은 트리거나 지연
--   집계가 필요한데(그리고 트리거는 경합에 또 취약하다), 자리를 0..2 로 못 박으면 복합 PK 가
--   중복을 막는 것만으로 「보드·뷰당 최대 3행」이 따라 나온다 — 선언만으로 닫힌다.
--
-- ★ 뷰마다 따로다(J18). view_scope 가 PK 에 들어가야 BOARD 구성과 BACKLOG 구성이 서로를
--   밀어내지 않는다. (board_id, position) PK 나 boards 의 배열 한 칸이면 두 뷰가 같은 자리를
--   다투고, 「보드에서 3개를 고르면 백로그 구성이 지워지는」 동작이 된다.
--
-- ★ 비어 있으면 **현행 카드**를 그린다(스펙 §데이터 모델). 기본 행을 심지 않는 것이 ① 의
--   working_days NULL 과 같은 원칙이다 — 배포 순간 모든 보드의 카드가 바뀌면 안 된다.
--
-- ★ deleted_at 이 없다. ② 와 같은 이유로 엔티티가 아니라 보드가 소유한 설정 값 목록이다.
CREATE TABLE IF NOT EXISTS board_card_layout_fields (
    board_id   UUID         NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    view_scope VARCHAR(16)  NOT NULL,
    position   SMALLINT     NOT NULL,
    field_key  VARCHAR(128) NOT NULL,
    -- 이 PK 인덱스의 leftmost prefix(board_id)가 FK 조회·CASCADE 점검을 덮으므로 board_id
    -- 전용 인덱스를 따로 두지 않는다 — DATA.md §7 의 의도를 충족하면서 중복 인덱스를 피한다
    -- (② board_non_working_dates · V500:36-38 과 같은 판단).
    PRIMARY KEY (board_id, view_scope, position),
    CHECK (position BETWEEN 0 AND 2),
    CHECK (view_scope IN ('BOARD', 'BACKLOG'))
);

COMMENT ON TABLE  board_card_layout_fields            IS '보드 카드에 얹을 필드 — 뷰(BOARD/BACKLOG)마다 최대 3개(J17·J18). 비어 있으면 현행 카드를 그린다(기본 행을 심지 않는 것이 무변경 보존이다)';
COMMENT ON COLUMN board_card_layout_fields.board_id   IS '소유 보드. 보드가 삭제되면 함께 사라진다(ON DELETE CASCADE — 고아 행을 남기지 않는다)';
COMMENT ON COLUMN board_card_layout_fields.view_scope IS 'BOARD / BACKLOG — 어느 뷰의 카드인가(J18). PK 에 들어가 있어 두 뷰의 구성이 서로를 밀어내지 않는다';
COMMENT ON COLUMN board_card_layout_fields.position   IS '카드에서의 자리(0..2). ★상한 3(J17)을 개수가 아니라 값의 범위로 표현한 것이며, 복합 PK 의 중복 금지와 합쳐져 트리거 없이 상한이 닫힌다. 서비스 사전 검사는 이 제약을 대신하지 않는다(#444 X1)';
COMMENT ON COLUMN board_card_layout_fields.field_key  IS '표준 필드 키 또는 커스텀 필드 키. 고른 필드가 그 이슈에 없으면 그 카드에서만 생략한다 — 빈 칸을 그리지 않는다(스펙 E4)';

-- ── ④ board_detail_view_fields — 상세 보기 탭 (J46·J47·J48) ───────────────────
--
-- ★★ 그룹 4종은 어디서 왔나. **J47 원문 그대로다** —
--   "different groups of fields: General fields, Date fields, People, and Links."
--   지라의 Issue Detail View 설정 화면이 고를 수 있는 필드를 이 네 구획으로 나눠 보여주고,
--   GENERAL·DATE·PEOPLE·LINKS 는 그 네 구획의 저장값이다. 우리가 정한 분류가 아니라
--   패리티 대상의 분류이므로 마음대로 늘리거나 이름을 바꾸지 않는다 — 늘리려면 지라 근거부터 댄다.
--
-- ★★ 그 근거 문서가 지라 **Data Center** 다(confluence.atlassian.com/jirasoftwareserver,
--   J46~J49). 지라 **클라우드**는 이 탭을 보드 설정에서 걷어내 work type 레이아웃
--   (Settings > Screens)으로 옮겼으므로 **보드 단위 Issue Detail View 는 클라우드에 대응 화면이
--   없다.** 그럼에도 보드 설정으로 내는 것이 확정이라, 근거가 DC 뿐이라는 사실을 스펙이
--   편차 **X10** 으로 명시했다. 이 주석은 그 X10 과 **짝**이다 — 나중에 「클라우드에 없는데 왜
--   있지」로 지워지는 것을 막는 자리이고, 지우려면 X10 을 먼저 읽어야 한다.
--
-- ★ 그래서 field_group 에는 CHECK 를 건다. 허용값이 지라 화면 구획(J47)에 묶여 있어 값 종류가
--   늘 일이 없다. ★초판은 여기에 「① 의 time_tracking 에 CHECK 를 걸지 않은 것과 갈리는
--   지점」이라고 적었는데 **이제 갈리지 않는다** — ① 도 boards_time_tracking_allowed 를 받는다.
--   ★그리고 초판이 든 근거 「스펙이 이 테이블에만 CHECK 를 명시했다」는 **애초에 사실이 아니다.**
--   스펙 §데이터 모델은 ③ board_card_layout_fields 에도 CHECK 를 둘(position · view_scope) 적었고
--   ③ 절이 실제로 그 둘을 갖고 있다. 남는 사실은 「스펙이 time_tracking 에만 CHECK 를 안 썼다」뿐이고,
--   그것이 구분의 근거가 못 된다는 판정이 ① 의 ★★ 다.
--
-- ★ position 에 상한이 **없다.** ③ 의 CHECK (position BETWEEN 0 AND 2) 를 여기로 복사해 오지 말 것 —
--   상한 3은 카드(J17)의 제약이고, 상세 보기는 J48 이 순서(드래그로 위아래)만 말한다.
--   그룹 안에서 몇 개를 보여줄지는 지라가 제한하지 않는다.
--
-- ★ 그룹이 PK 에 들어간다. 그래야 네 구획이 각자 0번 자리를 가진다 — (board_id, position) PK 면
--   한 구획에 필드를 넣을 때 다른 구획의 같은 자리를 밀어낸다(③ 의 view_scope 와 같은 판단).
--
-- ★ 비어 있으면 **현행 상세 화면**을 그린다. 기본 행을 심지 않는 것이 ①의 working_days NULL ·
--   ③의 빈 카드 레이아웃과 같은 원칙이다 — 배포 순간 모든 보드의 상세가 바뀌면 안 된다.
--
-- ★ deleted_at 이 없다. ②·③ 과 같은 이유로 엔티티가 아니라 보드가 소유한 설정 값 목록이다.
--
-- ★ 되돌리기 — 위 머리말의 DROP 목록에 이 한 줄을 더하면 완전 원복이다.
--     DROP TABLE board_detail_view_fields;
CREATE TABLE IF NOT EXISTS board_detail_view_fields (
    board_id    UUID         NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    field_group VARCHAR(16)  NOT NULL,
    position    SMALLINT     NOT NULL,
    field_key   VARCHAR(128) NOT NULL,
    -- 이 PK 인덱스의 leftmost prefix(board_id)가 FK 조회·CASCADE 점검을 덮으므로 board_id
    -- 전용 인덱스를 따로 두지 않는다 — DATA.md §7 의 의도를 충족하면서 중복 인덱스를 피한다
    -- (②·③ · V500:36-38 과 같은 판단).
    PRIMARY KEY (board_id, field_group, position),
    CHECK (field_group IN ('GENERAL', 'DATE', 'PEOPLE', 'LINKS'))
);

COMMENT ON TABLE  board_detail_view_fields             IS '이슈 상세 보기에 보여줄 필드 — 보드 단위 구성(J46). 그룹 4종(J47)으로 나뉘고 그룹 안에서 순서를 갖는다(J48). 비어 있으면 현행 상세 화면을 그린다';
COMMENT ON COLUMN board_detail_view_fields.board_id    IS '소유 보드. 보드가 삭제되면 함께 사라진다(ON DELETE CASCADE — 고아 행을 남기지 않는다)';
COMMENT ON COLUMN board_detail_view_fields.field_group IS 'GENERAL / DATE / PEOPLE / LINKS — J47 원문의 네 구획(General fields · Date fields · People · Links)이 출처다. 근거 문서가 지라 DC 뿐이라는 사실은 스펙 편차 X10 에 있다. PK 에 들어가 있어 네 구획이 각자 0번 자리를 가진다';
COMMENT ON COLUMN board_detail_view_fields.position    IS '그룹 안에서의 순서(J48 — 드래그로 위아래). ★상한이 없다. 카드 레이아웃의 0..2(J17)는 카드의 제약이므로 여기로 복사해 오지 말 것';
COMMENT ON COLUMN board_detail_view_fields.field_key   IS '표준 필드 키 또는 커스텀 필드 키. 모달과 사이드패널 두 표현이 같은 구성을 읽는다(스펙 R7c — 여는 방식에 따라 다르게 보이면 안 된다)';

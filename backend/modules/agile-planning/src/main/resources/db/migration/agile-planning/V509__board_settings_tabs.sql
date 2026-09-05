-- 보드 설정 잔여 4탭의 저장 칸 — boards 설정 3칸(추정·작업일) + 비근무일 테이블 (부채 177)
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).
--
-- 설계 정본. docs/specs/2026-09-05-board-settings-remaining-tabs-177.md §데이터 모델 · R5·R6 ·
-- docs/plans/2026-09-05-board-settings-remaining-tabs-177.md Task 1.
-- 지라 근거. J36(시간 추적 2종) · J38(표준 근무일) · J39(비근무일) · J40(타임존).
--
-- 왜 한 V번호인가. 네 탭이 각자 마이그레이션을 내면 V번호 동시 브랜치 충돌을 네 번 상대한다.
-- 한 파일에 몰아 한 번만 상대하는 것이 4탭을 한 PR 로 묶는 유일한 기술적 이득이다(plan §착수 시점).
--
-- ★★ 이 파일의 핵심 판단은 「무엇을 NOT NULL 로 두지 **않았는가**」다. 아래 ① 참조.
--
-- ★★ 되돌리기 — **무조건** 완전 원복된다. 데이터 손실은 설정값뿐이다.
--
--     DROP TABLE board_card_layout_fields;
--     DROP TABLE board_non_working_dates;
--     ALTER TABLE boards
--         DROP COLUMN time_tracking,
--         DROP COLUMN working_days,
--         DROP COLUMN board_timezone;
--
--   V508 은 되돌리기가 **조건부**였다(state_key 를 NOT NULL 로 되돌리는 줄이 NULL 행 하나에도
--   실패한다). 여기는 그렇지 않다 — 제약을 다시 조이는 줄이 없고, 신설한 칸·테이블에 의존하는
--   기존 객체도 없어 순서와 데이터에 관계없이 성공한다. 「무조건」이라고 쓸 수 있는 근거가 이것이다.
--   되돌리면 4탭의 설정값이 사라지고 보드는 마이그레이션 이전 동작으로 정확히 돌아간다 —
--   working_days 가 NULL 이었으므로 번다운은 애초에 달력일 전부를 돌고 있었다(①의 ★★).
--
--   ★ 위 목록은 ①·②·③ 의 것이다. 같은 V번호에 board_detail_view_fields 가 뒤이어 붙으므로
--   그 DROP 도 함께 세야 완전 원복이 된다.
--   산문은 기계가 안 읽는다 — 되돌리기를 실제로 실행해 스키마가 적용 전과 같은지 재는 것은
--   BoardSettingsIdempotencyTest 다(plan Task 4 REFACTOR · 부채 161).

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
-- ★ time_tracking 에 CHECK (IN ('NONE','REMAINING_AND_SPENT')) 를 걸지 **않았다.** 빠뜨린 것이
--   아니라 스펙 §데이터 모델이 CHECK 없이 선언했기 때문이다(같은 절이 board_card_layout_fields ·
--   board_detail_view_fields 에는 CHECK 를 명시했으므로 누락이 아니라 구분이다).
--   허용값 판정은 EstimationSettingsService(Task 9)가 진다. 값 종류가 늘 때 마이그레이션이
--   따라붙지 않아도 되는 쪽을 택한 것이며, 이 선택을 뒤집으려면 스펙을 먼저 고친다.

ALTER TABLE boards
    ADD COLUMN time_tracking  VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN working_days   VARCHAR(3)[] NULL,
    ADD COLUMN board_timezone VARCHAR(64)  NULL;

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
CREATE TABLE board_non_working_dates (
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
CREATE TABLE board_card_layout_fields (
    board_id   UUID         NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    view_scope VARCHAR(16)  NOT NULL,
    position   SMALLINT     NOT NULL,
    field_key  VARCHAR(128) NOT NULL,
    PRIMARY KEY (board_id, view_scope, position),
    CHECK (position BETWEEN 0 AND 2),
    CHECK (view_scope IN ('BOARD', 'BACKLOG'))
);

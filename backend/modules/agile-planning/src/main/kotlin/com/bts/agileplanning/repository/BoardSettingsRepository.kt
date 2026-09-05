// 보드 설정 4탭의 저장 칸 접근 — 카드 레이아웃 · 시간 추적 · 근무일 · 상세 보기 필드 (V509 · 부채 177)

package com.bts.agileplanning.repository

import com.bts.agileplanning.jooq.tables.references.BOARDS
import com.bts.agileplanning.jooq.tables.references.BOARD_CARD_LAYOUT_FIELDS
import com.bts.agileplanning.jooq.tables.references.BOARD_DETAIL_VIEW_FIELDS
import com.bts.agileplanning.jooq.tables.references.BOARD_NON_WORKING_DATES
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 근무일 탭이 보드 하나에서 읽는 세 값 (R5 · J38·J39·J40).
 *
 * ## ★ null 이 두 층이다 — 뜻이 다르다
 * - [BoardSettingsRepository.findWorkingDays] 가 **통째로 null** 을 주면 「보드가 없다(또는 soft-deleted)」다.
 * - 이 객체 안의 [standardDays] 가 null 이면 「**설정 안 함**」이다 — 빈 리스트와 **다른 값**이다.
 *
 * 그 구분이 스펙 R6 그 자체다. `working_days` 를 `NOT NULL DEFAULT '{MON..FRI}'` 로 두면
 * **기존 모든 스프린트의 번다운이 배포 순간 바뀐다.** NULL 이 「미설정 = 달력일 전부(현행 유지)」이고,
 * 빈 배열은 사용자가 명시적으로 0개를 저장한 상태다(그 자체는 400 으로 막을 몫이지만 —
 * 스펙 E1 — 저장 계층은 두 값을 뭉개지 않는다).
 *
 * @property standardDays 표준 근무일 요일 집합(`MON`..`SUN`). **null = 미설정**(≠ 빈 리스트).
 * @property timezone IANA 타임존. null = 미설정 = UTC(R10 · E7).
 * @property nonWorkingDates 비근무일. **날짜 오름차순**이다.
 */
data class BoardWorkingDays(
    val standardDays: List<String>?,
    val timezone: String?,
    val nonWorkingDates: List<LocalDate>,
)

/**
 * 보드 설정 4탭(부채 177)의 영속 접근 — `V509` 가 낸 칸들의 정본.
 *
 * ## 왜 [BoardRepository] 와 분리했나 (스펙 C-1 · 부채 157)
 * `BoardRepository.kt` 는 이미 `DEVELOPMENT.md §2.1` 의 파일 줄수 상한을 넘겼다. 설정 4축의
 * 읽기·쓰기를 거기 넣으면 그 부채가 깊어진다 — `#444` 가 [BoardColumnStateRepository] 를 뺀 것과
 * **같은 이유이고 같은 모양**이다. 설정 4탭은 보드 애그리게이트의 속성이지만 자체 응집이 있다.
 *
 * ## 담는 것
 *
 * | 축 | 저장 칸 | 키 |
 * |---|---|---|
 * | 카드 레이아웃 | `board_card_layout_fields` | `(board_id, view_scope, position)` |
 * | 시간 추적 | `boards.time_tracking` | `boards.id` |
 * | 근무일 | `boards.working_days` · `boards.board_timezone` · `board_non_working_dates` | `boards.id` |
 * | 상세 보기 필드 | `board_detail_view_fields` | `(board_id, field_group, position)` |
 *
 * ## 값 검증은 여기 없다 — DB 와 서비스가 나눠 진다
 * `view_scope` · `field_group` · `time_tracking` 의 허용값과 카드 3칸 상한은 **V509 의 CHECK 제약**이
 * 진다. 리포지터리가 그 판정을 흉내 내지 않는다 — 사전 검사는 동시 저장 경합을 못 막는다(`#444` X1).
 * 서비스의 검증(400/409)은 **사용자에게 이유를 주려고** 있는 것이지 DB 제약을 대신하지 않는다.
 * 그래서 이 클래스의 스코프·그룹 인자는 [String] 이다 — [BoardColumnStateRepository.updateColumnCategory]
 * 가 카테고리 문자열을 그대로 받는 것과 같은 판단이다.
 *
 * @param dsl jOOQ DSLContext
 */
@Repository
class BoardSettingsRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 카드 레이아웃 (R2·R3 · J17·J18) ────────────────────────────────────────

    /**
     * 보드의 카드 레이아웃을 **뷰별로** 한 번의 조회로 읽는다 (N1 · J18).
     *
     * 뷰마다 조회를 나누면 보드·백로그 렌더마다 왕복이 는다. 스크럼 보드는 두 뷰가 서로 다른
     * 구성을 가질 수 있으므로(R3) 결과는 `view_scope` 로 갈린다.
     *
     * @param boardId 대상 보드 UUID.
     * @return `viewScope → 필드 키 목록(position 오름차순)`. **구성이 없는 뷰는 키가 아예 없다** —
     *   호출자는 `orEmpty()` 로 받는다. 빈 구성은 「현행 카드를 그린다」는 뜻이다(V509 ③).
     */
    @Transactional(readOnly = true)
    fun findCardLayout(boardId: UUID): Map<String, List<String>> =
        dsl.select(BOARD_CARD_LAYOUT_FIELDS.VIEW_SCOPE, BOARD_CARD_LAYOUT_FIELDS.FIELD_KEY)
            .from(BOARD_CARD_LAYOUT_FIELDS)
            .where(BOARD_CARD_LAYOUT_FIELDS.BOARD_ID.eq(boardId))
            .orderBy(BOARD_CARD_LAYOUT_FIELDS.POSITION.asc())
            .fetch()
            .groupBy({ it[BOARD_CARD_LAYOUT_FIELDS.VIEW_SCOPE] }, { it[BOARD_CARD_LAYOUT_FIELDS.FIELD_KEY] })
            .toFieldKeyMap()

    /**
     * **한 뷰의** 카드 레이아웃을 통째로 교체한다 (R3 · J18).
     *
     * 술어에 `view_scope` 를 함께 걸어 **다른 뷰의 구성은 건드리지 않는다** — 보드 뷰를 저장했다고
     * 백로그 구성이 사라지면 J18 이 무너진다. 추가·삭제를 각각 열지 않는 이유는
     * [BoardColumnStateRepository.replaceStates] 와 같다: 「지금 이 뷰의 구성」이 클라이언트와
     * 서버 사이에서 갈릴 수 있고, 집합 전체를 받으면 한 요청 안에서 판정할 수 있다.
     *
     * `position` 은 목록 순서 그대로 0 부터 매긴다. 4개 이상이면 `position = 3` 이
     * **V509 의 `CHECK (position BETWEEN 0 AND 2)`** 에 걸려 거부되고(SQLSTATE 23514),
     * 삭제와 삽입이 한 트랜잭션이라 **이전 구성이 그대로 살아남는다.**
     *
     * @param boardId 대상 보드 UUID.
     * @param viewScope `"BOARD"` 또는 `"BACKLOG"`. 다른 값은 DB CHECK 가 거부한다.
     * @param fieldKeys 새 구성. 순서가 곧 카드에서의 자리다. 빈 리스트를 허용한다(= 현행 카드).
     * @throws org.springframework.dao.DataIntegrityViolationException 4개 이상이거나 미지원 스코프일 때.
     */
    @Transactional
    fun replaceCardLayout(
        boardId: UUID,
        viewScope: String,
        fieldKeys: List<String>,
    ) {
        log.debug("카드 레이아웃 교체 — boardId={}, viewScope={}, fields={}", boardId, viewScope, fieldKeys)

        dsl.deleteFrom(BOARD_CARD_LAYOUT_FIELDS)
            .where(BOARD_CARD_LAYOUT_FIELDS.BOARD_ID.eq(boardId))
            .and(BOARD_CARD_LAYOUT_FIELDS.VIEW_SCOPE.eq(viewScope))
            .execute()

        if (fieldKeys.isEmpty()) return

        // foldIndexed 로 단계를 이어 붙인다 — forEach 로 던져 버리면 detekt IgnoredReturnValue 다.
        fieldKeys.foldIndexed(
            dsl.insertInto(
                BOARD_CARD_LAYOUT_FIELDS,
                BOARD_CARD_LAYOUT_FIELDS.BOARD_ID,
                BOARD_CARD_LAYOUT_FIELDS.VIEW_SCOPE,
                BOARD_CARD_LAYOUT_FIELDS.POSITION,
                BOARD_CARD_LAYOUT_FIELDS.FIELD_KEY,
            ),
        ) { position, step, key -> step.values(boardId, viewScope, position.toShort(), key) }
            .execute()
    }

    // ── 시간 추적 (R4 · J36·J37) ──────────────────────────────────────────────

    /**
     * 보드의 시간 추적 설정을 읽는다 (J36).
     *
     * `boards.time_tracking` 은 `NOT NULL DEFAULT 'NONE'` 이라 **null 은 「보드가 없다」는 뜻뿐**이다
     * (soft-deleted 포함).
     *
     * 값은 보드 종류와 무관하게 그대로 돌려준다 — 칸반에서 무시하는 것은 **읽는 쪽**의 몫이다(E6).
     * 여기서 걸러 내면 스크럼 → 칸반 → 스크럼 왕복에 값이 사라진 것처럼 보인다.
     *
     * @param boardId 대상 보드 UUID.
     * @return `"NONE"` 또는 `"REMAINING_AND_SPENT"`. 보드가 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findTimeTracking(boardId: UUID): String? =
        dsl.select(BOARDS.TIME_TRACKING)
            .from(BOARDS)
            .where(BOARDS.ID.eq(boardId))
            .and(BOARDS.DELETED_AT.isNull)
            .fetchOne(BOARDS.TIME_TRACKING)

    /**
     * 시간 추적 설정을 갱신한다 (J36).
     *
     * 「스크럼 보드에서만 변경 가능」(J37 · 409)은 **서비스**가 진다 — 보드 종류에 따른 조작 가능 여부는
     * 도메인 규칙이고, 리포지터리가 조용히 무시하면 서비스가 409 를 낼 근거를 잃는다.
     *
     * @param boardId 대상 보드 UUID.
     * @param timeTracking `"NONE"` 또는 `"REMAINING_AND_SPENT"`. 다른 값은 DB CHECK 가 거부한다.
     * @return 갱신됐으면 true. 없거나 soft-deleted 이면 false(404 신호) —
     *   [BoardRepository.updateName] 과 같은 규약이다.
     */
    @Transactional
    fun updateTimeTracking(
        boardId: UUID,
        timeTracking: String,
    ): Boolean {
        log.debug("시간 추적 갱신 — boardId={}, timeTracking={}", boardId, timeTracking)
        return dsl.update(BOARDS)
            .set(BOARDS.TIME_TRACKING, timeTracking)
            .set(BOARDS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(BOARDS.ID.eq(boardId))
            .and(BOARDS.DELETED_AT.isNull)
            .execute() > 0
    }

    // ── 근무일 (R5·R6 · J38·J39·J40) ──────────────────────────────────────────

    /**
     * 근무일 설정 세 값을 함께 읽는다 (R5).
     *
     * ★**NULL 과 빈 배열을 뭉개지 않는다.** `working_days` 가 SQL NULL 이면 [BoardWorkingDays.standardDays]
     * 도 null 이고(= 미설정 = 달력일 전부), `{}` 로 저장돼 있으면 **빈 리스트**다. 그 구분이 R6 이다 —
     * 뭉개면 설정을 한 번도 안 만진 보드의 번다운이 배포 순간 바뀐다.
     *
     * @param boardId 대상 보드 UUID.
     * @return 설정 세 값. **보드가 없거나 soft-deleted 이면 null**(안쪽 null 과 뜻이 다르다).
     */
    @Transactional(readOnly = true)
    fun findWorkingDays(boardId: UUID): BoardWorkingDays? {
        val row =
            dsl.select(BOARDS.WORKING_DAYS, BOARDS.BOARD_TIMEZONE)
                .from(BOARDS)
                .where(BOARDS.ID.eq(boardId))
                .and(BOARDS.DELETED_AT.isNull)
                .fetchOne() ?: return null

        val nonWorkingDates =
            dsl.select(BOARD_NON_WORKING_DATES.DATE)
                .from(BOARD_NON_WORKING_DATES)
                .where(BOARD_NON_WORKING_DATES.BOARD_ID.eq(boardId))
                .orderBy(BOARD_NON_WORKING_DATES.DATE.asc())
                .fetch(BOARD_NON_WORKING_DATES.DATE)
                .filterNotNull()

        return BoardWorkingDays(
            // ?. 가 「미설정(NULL)」을 그대로 통과시킨다. filterNotNull 은 배열 **원소**의 null 만 턴다.
            standardDays = row[BOARDS.WORKING_DAYS]?.filterNotNull(),
            timezone = row[BOARDS.BOARD_TIMEZONE],
            nonWorkingDates = nonWorkingDates,
        )
    }

    /**
     * 표준 근무일과 타임존을 갱신한다 (J38 · J40).
     *
     * 두 칸을 한 문장에서 쓰는 이유는 둘이 같은 탭의 한 저장 단위이기 때문이다 —
     * 나눠 쓰면 요일만 반영되고 타임존이 빠진 중간 상태가 생긴다.
     *
     * ★[standardDays] 에 **null 을 주면 SQL NULL(미설정)** 이 되고, 빈 리스트를 주면 `{}` 가 된다.
     * 두 입력이 **다른 결과**를 낳는 것이 이 메서드의 계약이다(R6). 0개 저장을 400 으로 막는 것은
     * 서비스의 몫이다(E1) — 저장 계층은 그 판정을 흉내 내지 않는다.
     *
     * @param boardId 대상 보드 UUID.
     * @param standardDays `MON`..`SUN` 요일 키 목록. **null 이면 미설정으로 되돌린다.**
     * @param timezone IANA 타임존. null 이면 미설정(UTC)으로 되돌린다. IANA 검증은 서비스가 한다.
     * @return 갱신됐으면 true. 없거나 soft-deleted 이면 false(404 신호).
     */
    @Transactional
    fun updateWorkingDays(
        boardId: UUID,
        standardDays: List<String>?,
        timezone: String?,
    ): Boolean {
        log.debug("근무일 갱신 — boardId={}, standardDays={}, timezone={}", boardId, standardDays, timezone)
        return dsl.update(BOARDS)
            .set(BOARDS.WORKING_DAYS, standardDays?.toTypedArray<String?>())
            .set(BOARDS.BOARD_TIMEZONE, timezone)
            .set(BOARDS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(BOARDS.ID.eq(boardId))
            .and(BOARDS.DELETED_AT.isNull)
            .execute() > 0
    }

    /**
     * 비근무일 목록을 통째로 교체한다 (J39).
     *
     * 스프린트 기간 **밖**의 날짜도 저장한다 — 계산에서 자연히 무시된다(E8). 기간을 아는 것은
     * 스프린트지 보드가 아니라서, 여기서 거르면 스프린트가 바뀔 때마다 설정이 소실된다.
     *
     * @param boardId 대상 보드 UUID.
     * @param dates 새 비근무일 목록. 빈 리스트는 「비근무일 없음」이다.
     *   같은 날짜가 두 번 들어오면 `PRIMARY KEY (board_id, date)` 가 거부한다 —
     *   호출자가 집합으로 정규화해서 넘긴다(리포지터리가 조용히 중복을 삼키면 사용자는 자기가
     *   무엇을 저장했는지 모른다).
     */
    @Transactional
    fun replaceNonWorkingDates(
        boardId: UUID,
        dates: List<LocalDate>,
    ) {
        log.debug("비근무일 교체 — boardId={}, count={}", boardId, dates.size)

        dsl.deleteFrom(BOARD_NON_WORKING_DATES)
            .where(BOARD_NON_WORKING_DATES.BOARD_ID.eq(boardId))
            .execute()

        if (dates.isEmpty()) return

        dates.fold(
            dsl.insertInto(
                BOARD_NON_WORKING_DATES,
                BOARD_NON_WORKING_DATES.BOARD_ID,
                BOARD_NON_WORKING_DATES.DATE,
            ),
        ) { step, date -> step.values(boardId, date) }
            .execute()
    }

    // ── 상세 보기 필드 (R7 · J46·J47·J48) ─────────────────────────────────────

    /**
     * 이슈 상세 보기 구성을 **그룹별로** 한 번의 조회로 읽는다 (J47 · J48).
     *
     * 모달과 사이드패널 두 표현이 **이 한 결과**를 읽는다(R7c) — 한쪽만 반영되면 같은 이슈가
     * 여는 방식에 따라 다르게 보인다.
     *
     * @param boardId 대상 보드 UUID.
     * @return `fieldGroup → 필드 키 목록(position 오름차순)`. 구성이 없는 그룹은 키가 아예 없다 —
     *   호출자는 `orEmpty()` 로 받는다. 전부 비면 현행 상세 화면을 그린다(V509 ④).
     */
    @Transactional(readOnly = true)
    fun findDetailViewFields(boardId: UUID): Map<String, List<String>> =
        dsl.select(BOARD_DETAIL_VIEW_FIELDS.FIELD_GROUP, BOARD_DETAIL_VIEW_FIELDS.FIELD_KEY)
            .from(BOARD_DETAIL_VIEW_FIELDS)
            .where(BOARD_DETAIL_VIEW_FIELDS.BOARD_ID.eq(boardId))
            .orderBy(BOARD_DETAIL_VIEW_FIELDS.POSITION.asc())
            .fetch()
            .groupBy({ it[BOARD_DETAIL_VIEW_FIELDS.FIELD_GROUP] }, { it[BOARD_DETAIL_VIEW_FIELDS.FIELD_KEY] })
            .toFieldKeyMap()

    /**
     * **한 그룹의** 상세 보기 구성을 통째로 교체한다 (J47 · J48).
     *
     * 술어에 `field_group` 을 걸어 다른 그룹은 건드리지 않는다 — 네 구획은 각자 0번 자리를 갖는다.
     *
     * ★**개수 상한이 없다.** 카드 레이아웃의 0..2(J17)는 카드의 제약이므로 여기로 복사해 오지 않는다
     * (V509 ④ 주석과 같은 경고).
     *
     * @param boardId 대상 보드 UUID.
     * @param fieldGroup `"GENERAL"` · `"DATE"` · `"PEOPLE"` · `"LINKS"`. 다른 값은 DB CHECK 가 거부한다.
     * @param fieldKeys 새 구성. 순서가 곧 그룹 안 표시 순서다(드래그 결과). 빈 리스트를 허용한다.
     * @throws org.springframework.dao.DataIntegrityViolationException 미지원 그룹일 때.
     */
    @Transactional
    fun replaceDetailViewFields(
        boardId: UUID,
        fieldGroup: String,
        fieldKeys: List<String>,
    ) {
        log.debug("상세 보기 필드 교체 — boardId={}, group={}, fields={}", boardId, fieldGroup, fieldKeys)

        dsl.deleteFrom(BOARD_DETAIL_VIEW_FIELDS)
            .where(BOARD_DETAIL_VIEW_FIELDS.BOARD_ID.eq(boardId))
            .and(BOARD_DETAIL_VIEW_FIELDS.FIELD_GROUP.eq(fieldGroup))
            .execute()

        if (fieldKeys.isEmpty()) return

        fieldKeys.foldIndexed(
            dsl.insertInto(
                BOARD_DETAIL_VIEW_FIELDS,
                BOARD_DETAIL_VIEW_FIELDS.BOARD_ID,
                BOARD_DETAIL_VIEW_FIELDS.FIELD_GROUP,
                BOARD_DETAIL_VIEW_FIELDS.POSITION,
                BOARD_DETAIL_VIEW_FIELDS.FIELD_KEY,
            ),
        ) { position, step, key -> step.values(boardId, fieldGroup, position.toShort(), key) }
            .execute()
    }
}

/**
 * jOOQ `groupBy` 결과(키·값이 nullable)를 호출자가 쓰는 non-null 모양으로 좁힌다.
 *
 * 두 축(`view_scope` · `field_group`)이 같은 변환을 쓴다. 두 칸 모두 `NOT NULL` 이라
 * 여기서 걸러 나가는 행은 없다 — `!!` 를 쓰지 않으려는 타입 정리다(DEVELOPMENT.md §1).
 */
private fun Map<String?, List<String?>>.toFieldKeyMap(): Map<String, List<String>> =
    mapNotNull { (group, keys) -> group?.let { it to keys.filterNotNull() } }.toMap()

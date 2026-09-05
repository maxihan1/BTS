// agile-planning BoardSettingsRepository 통합 테스트 — 설정 4축(카드 레이아웃·시간 추적·근무일·상세 필드) 읽기·쓰기 (V509)

package com.bts.agileplanning.repository

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.jooq.tables.references.BOARD_CARD_LAYOUT_FIELDS
import com.bts.agileplanning.jooq.tables.references.BOARD_DETAIL_VIEW_FIELDS
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * CHECK 제약 위반 SQLSTATE.
 *
 * 아무 예외로나 재면 오타·테이블 부재(42P01)도 통과해 단언이 공허해진다 —
 * 같은 모듈 [com.bts.agileplanning.migration.BoardCardLayoutSchemaTest] 가 이미 못 박은 규율이다.
 */
private const val CHECK_VIOLATION = "23514"

/**
 * [BoardSettingsRepository] 통합 테스트 — 보드 설정 4탭이 실제로 **DB 에** 앉는지.
 *
 * ## 왜 [BoardRepository] 가 아니라 별도 리포지터리인가 (스펙 C-1 · 부채 157)
 * `BoardRepository.kt` 는 이미 `DEVELOPMENT.md §2.1` 의 파일 줄수 상한을 넘겼다. 설정 4축의
 * 읽기·쓰기를 거기 넣으면 그 부채가 깊어진다. `#444` 가 [BoardColumnStateRepository] 를 뺀 것과
 * 같은 이유이고, 이 task 의 존재 이유 자체가 **그 파일을 키우지 않는 것**이다.
 *
 * ## 이 클래스가 지는 판정 (10축)
 *
 * | 축 | 무엇 | 근거 |
 * |---|---|---|
 * | ① 뷰 축 | 카드 레이아웃이 `BOARD`/`BACKLOG` **각자의** 구성을 갖는다 | R3 · J18 |
 * | ② 뷰 격리 | 한 뷰를 교체해도 다른 뷰가 지워지지 않는다 | R3 |
 * | ③ 덮어쓰기 | 교체가 이전 자리를 **실제로 지운다**(잔여 행 0) | R2 |
 * | ④ 순서 | 물리적 행 순서가 뒤섞여도 `position` 순으로 읽는다 | J17 · J48 |
 * | ⑤ 보드 격리 | 다른 보드의 설정이 섞이지 않는다 | — |
 * | ⑥ 상한 | 한 뷰 4개는 **DB CHECK** 가 거부하고 기존 구성이 살아남는다 | E3 · #444 X1 |
 * | ⑦ 시간 추적 | 기본 `NONE` · 갱신 · 없는 보드는 false | R4 · J36 |
 * | ⑧ **NULL ≠ 빈 배열** | `working_days` 미설정(NULL)과 빈 배열이 **다르게** 읽힌다 | **R6** |
 * | ⑨ 비근무일 | 교체가 이전 날짜를 지우고 날짜 오름차순으로 읽힌다 | R5 · J39 |
 * | ⑩ 그룹 축 | 상세 필드가 그룹 4종 각자의 순서를 갖고 서로를 밀어내지 않는다 | R7 · J47 · J48 |
 *
 * ★ ⑧ 이 이 파일에서 가장 중요한 축이다. 「쓰고 읽으면 같다」만 재면 NULL 과 `{}` 를 한 값으로
 * 뭉갠 구현도 통과한다. 그러면 **미설정 보드의 번다운이 배포 순간 바뀐다**(스펙 R6 · V509 ① 의 ★★).
 *
 * ★ ①②④⑩ 은 「저장하고 읽으면 같다」로는 못 가르는 자리다 — 뷰·그룹 축이 없는 구현도,
 * `ORDER BY` 가 없는 구현도 그 단언만으로는 초록이다.
 *
 * ## 설정 공유
 * [AgilePlanningTestcontainersConfig] 의 singleton PostgreSQL 컨테이너를 재사용한다.
 * 스키마(제약·컬럼) 자체의 검증은 전용 컨테이너를 쓰는 `migration` 패키지가 진다 —
 * 여기는 **리포지터리가 그 스키마를 제대로 쓰는지**만 본다.
 */
@SpringBootTest(classes = [AgilePlanningTestBootApplication::class])
@Import(AgilePlanningTestcontainersConfig::class)
@ActiveProfiles("test")
// detekt VarCouldBeVal 오탐 — 필드 주입은 `lateinit var` 뿐이고 `lateinit` 은 val 에 못 쓴다.
// 형제 리포지터리 테스트 4종이 같은 자리로 detektTest 선재 red 를 차지하고 있다. 남의 것은 손대지 않되
// 신규 파일이 그 숫자를 늘리지는 않는다(모듈에 detekt-baseline.xml 이 없어 동결할 자리도 없다).
@Suppress("VarCouldBeVal")
class BoardSettingsRepositoryTest {
    @Autowired
    private lateinit var boardRepository: BoardRepository

    @Autowired
    private lateinit var settingsRepository: BoardSettingsRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    // ── ① 뷰 축 (R3 · J18) ────────────────────────────────────────────────────

    @Test
    fun `카드 레이아웃은 뷰마다 서로 다른 구성을 갖는다`() {
        val board = insertBoard()

        settingsRepository.replaceCardLayout(board.id, "BOARD", listOf("EPIC", "PRIORITY", "cf_story_points"))
        settingsRepository.replaceCardLayout(board.id, "BACKLOG", listOf("ESTIMATE", "LABELS", "DUE_DATE"))

        val layout = settingsRepository.findCardLayout(board.id)
        assertThat(layout["BOARD"]).containsExactly("EPIC", "PRIORITY", "cf_story_points")
        assertThat(layout["BACKLOG"]).containsExactly("ESTIMATE", "LABELS", "DUE_DATE")
    }

    // ── ② 뷰 격리 (R3) ────────────────────────────────────────────────────────

    @Test
    fun `한 뷰를 교체해도 다른 뷰의 구성은 남는다`() {
        // 뷰 축 없이 board_id 로만 지우는 구현을 가른다 — 그 구현도 「쓰고 읽으면 같다」는 통과한다.
        val board = insertBoard()
        settingsRepository.replaceCardLayout(board.id, "BACKLOG", listOf("ESTIMATE"))

        settingsRepository.replaceCardLayout(board.id, "BOARD", listOf("EPIC", "PRIORITY"))

        val layout = settingsRepository.findCardLayout(board.id)
        assertThat(layout["BACKLOG"]).containsExactly("ESTIMATE")
        assertThat(layout["BOARD"]).containsExactly("EPIC", "PRIORITY")
    }

    // ── ③ 덮어쓰기 (R2) ───────────────────────────────────────────────────────

    @Test
    fun `카드 레이아웃 교체가 이전 자리를 실제로 지운다`() {
        val board = insertBoard()
        settingsRepository.replaceCardLayout(board.id, "BOARD", listOf("EPIC", "PRIORITY", "LABELS"))

        settingsRepository.replaceCardLayout(board.id, "BOARD", listOf("ESTIMATE"))

        assertThat(settingsRepository.findCardLayout(board.id)["BOARD"]).containsExactly("ESTIMATE")
        // 저장된 행 자체를 본다 — 읽기가 3개 중 1개만 골라 주는 구현도 위 단언만으로는 통과한다.
        assertThat(rawCardLayout(board.id, "BOARD"))
            .containsExactlyInAnyOrderEntriesOf(mapOf<Short?, String?>(0.toShort() to "ESTIMATE"))
    }

    @Test
    fun `카드 레이아웃을 빈 목록으로 교체하면 그 뷰가 비고 키가 사라진다`() {
        val board = insertBoard()
        settingsRepository.replaceCardLayout(board.id, "BOARD", listOf("EPIC"))

        settingsRepository.replaceCardLayout(board.id, "BOARD", emptyList())

        assertThat(settingsRepository.findCardLayout(board.id)).doesNotContainKey("BOARD")
        assertThat(rawCardLayout(board.id, "BOARD")).isEmpty()
    }

    // ── ④ 순서 (J17 · J48) ────────────────────────────────────────────────────

    @Test
    fun `카드 레이아웃은 행이 뒤섞여 들어가 있어도 position 순으로 읽는다`() {
        // ORDER BY 가 없는 구현은 힙 순서(third·first·second)를 그대로 돌려준다 —
        // 단 [readWithSeqScan] 으로 인덱스 스캔을 꺼야 그렇다. 이유는 그 헬퍼의 KDoc 에 있다.
        val board = insertBoard()
        insertCardLayoutRow(board.id, "BOARD", 2, "third")
        insertCardLayoutRow(board.id, "BOARD", 0, "first")
        insertCardLayoutRow(board.id, "BOARD", 1, "second")

        val layout = readWithSeqScan { settingsRepository.findCardLayout(board.id) }

        assertThat(layout["BOARD"]).containsExactly("first", "second", "third")
    }

    // ── ⑤ 보드 격리 ───────────────────────────────────────────────────────────

    @Test
    fun `다른 보드의 카드 레이아웃이 섞이지 않고 쓰기도 옆 보드를 건드리지 않는다`() {
        val mine = insertBoard()
        val other = insertBoard()
        settingsRepository.replaceCardLayout(mine.id, "BOARD", listOf("EPIC"))

        settingsRepository.replaceCardLayout(other.id, "BOARD", listOf("PRIORITY", "LABELS"))

        assertThat(settingsRepository.findCardLayout(mine.id)["BOARD"]).containsExactly("EPIC")
        assertThat(settingsRepository.findCardLayout(other.id)["BOARD"]).containsExactly("PRIORITY", "LABELS")
    }

    // ── ⑥ 상한 (E3 · #444 X1) ─────────────────────────────────────────────────

    @Test
    fun `한 뷰에 4개를 넣으면 CHECK 가 거부하고 기존 구성이 살아남는다`() {
        // 상한은 서비스 사전 검사가 아니라 DB 가 진다(position BETWEEN 0 AND 2).
        // 그리고 삭제 후 삽입이 한 트랜잭션이라 거부되면 이전 구성이 그대로 남아야 한다.
        val board = insertBoard()
        settingsRepository.replaceCardLayout(board.id, "BOARD", listOf("EPIC"))

        val state =
            sqlStateOf {
                settingsRepository.replaceCardLayout(board.id, "BOARD", listOf("a", "b", "c", "d"))
            }

        assertThat(state).isEqualTo(CHECK_VIOLATION)
        assertThat(settingsRepository.findCardLayout(board.id)["BOARD"]).containsExactly("EPIC")
    }

    // ── ⑦ 시간 추적 (R4 · J36) ────────────────────────────────────────────────

    @Test
    fun `신규 보드의 시간 추적은 NONE 이고 갱신은 그 보드만 바꾼다`() {
        val mine = insertBoard()
        val other = insertBoard()
        assertThat(settingsRepository.findTimeTracking(mine.id)).isEqualTo("NONE")

        val updated = settingsRepository.updateTimeTracking(mine.id, "REMAINING_AND_SPENT")

        assertThat(updated).isTrue()
        assertThat(settingsRepository.findTimeTracking(mine.id)).isEqualTo("REMAINING_AND_SPENT")
        assertThat(settingsRepository.findTimeTracking(other.id)).isEqualTo("NONE")
    }

    @Test
    fun `없는 보드의 시간 추적은 조회가 null 이고 갱신이 false 다`() {
        val missing = UUID.randomUUID()

        assertThat(settingsRepository.findTimeTracking(missing)).isNull()
        assertThat(settingsRepository.updateTimeTracking(missing, "REMAINING_AND_SPENT")).isFalse()
    }

    // ── ⑧ NULL ≠ 빈 배열 (R6) ★ ───────────────────────────────────────────────

    @Test
    fun `근무일 미설정 보드와 빈 배열 보드가 서로 다르게 읽힌다`() {
        // ★이 PR 에서 가장 무른 자리. NULL 과 {} 를 한 값으로 뭉개면 미설정 보드의 번다운이
        //  배포 순간 바뀐다(R6). 「쓰고 읽으면 같다」만 재는 테스트는 그 구현도 통과시킨다.
        val untouched = insertBoard()
        val emptied = insertBoard()

        settingsRepository.updateWorkingDays(emptied.id, standardDays = emptyList(), timezone = null)

        assertThat(settingsRepository.findWorkingDays(untouched.id)?.standardDays)
            .describedAs("한 번도 설정 안 한 보드 — NULL 이어야 한다(미설정 = 달력일 전부)")
            .isNull()
        assertThat(settingsRepository.findWorkingDays(emptied.id)?.standardDays)
            .describedAs("빈 배열로 저장한 보드 — NULL 이 아니라 빈 리스트여야 한다")
            .isNotNull()
            .isEmpty()
    }

    @Test
    fun `근무일을 저장했다가 다시 미설정으로 되돌릴 수 있다`() {
        val board = insertBoard()
        settingsRepository.updateWorkingDays(board.id, listOf("MON", "TUE", "WED", "THU", "FRI"), "Asia/Seoul")
        assertThat(settingsRepository.findWorkingDays(board.id)?.standardDays)
            .containsExactly("MON", "TUE", "WED", "THU", "FRI")

        settingsRepository.updateWorkingDays(board.id, standardDays = null, timezone = null)

        val reset = settingsRepository.findWorkingDays(board.id)
        assertThat(reset?.standardDays).isNull()
        assertThat(reset?.timezone).isNull()
    }

    @Test
    fun `타임존만 설정해도 근무일은 미설정으로 남는다`() {
        // 스펙 E7 — 타임존은 저장되고 번다운은 현행(달력일 전부) 유지.
        val board = insertBoard()

        settingsRepository.updateWorkingDays(board.id, standardDays = null, timezone = "Asia/Seoul")

        val found = settingsRepository.findWorkingDays(board.id)
        assertThat(found?.timezone).isEqualTo("Asia/Seoul")
        assertThat(found?.standardDays).isNull()
    }

    @Test
    fun `없는 보드의 근무일은 조회가 null 이고 갱신이 false 다`() {
        // 바깥 null(보드 없음)과 안쪽 null(미설정)은 다른 뜻이다 — 404 신호가 미설정에 먹히면 안 된다.
        val missing = UUID.randomUUID()

        assertThat(settingsRepository.findWorkingDays(missing)).isNull()
        assertThat(settingsRepository.updateWorkingDays(missing, listOf("MON"), null)).isFalse()
    }

    // ── ⑨ 비근무일 (R5 · J39) ─────────────────────────────────────────────────

    @Test
    fun `비근무일은 날짜 오름차순으로 읽히고 교체가 이전 날짜를 지운다`() {
        val board = insertBoard()
        val (d1, d2, d3) = Triple(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 3))
        settingsRepository.replaceNonWorkingDates(board.id, listOf(d3, d1, d2))
        assertThat(settingsRepository.findWorkingDays(board.id)?.nonWorkingDates).containsExactly(d1, d2, d3)

        settingsRepository.replaceNonWorkingDates(board.id, listOf(d2))

        assertThat(settingsRepository.findWorkingDays(board.id)?.nonWorkingDates).containsExactly(d2)
    }

    @Test
    fun `비근무일은 보드별로 격리된다`() {
        val mine = insertBoard()
        val other = insertBoard()
        settingsRepository.replaceNonWorkingDates(mine.id, listOf(LocalDate.of(2026, 10, 3)))

        settingsRepository.replaceNonWorkingDates(other.id, listOf(LocalDate.of(2026, 12, 25)))

        assertThat(settingsRepository.findWorkingDays(mine.id)?.nonWorkingDates)
            .containsExactly(LocalDate.of(2026, 10, 3))
        assertThat(settingsRepository.findWorkingDays(other.id)?.nonWorkingDates)
            .containsExactly(LocalDate.of(2026, 12, 25))
    }

    // ── ⑩ 그룹 축 (R7 · J47 · J48) ────────────────────────────────────────────

    @Test
    fun `상세 필드는 그룹 4종이 각자의 순서를 갖는다`() {
        val board = insertBoard()

        settingsRepository.replaceDetailViewFields(board.id, "GENERAL", listOf("summary", "status", "cf_severity"))
        settingsRepository.replaceDetailViewFields(board.id, "DATE", listOf("dueDate"))
        settingsRepository.replaceDetailViewFields(board.id, "PEOPLE", listOf("assignee", "reporter"))
        settingsRepository.replaceDetailViewFields(board.id, "LINKS", listOf("issueLinks"))

        val fields = settingsRepository.findDetailViewFields(board.id)
        assertThat(fields.keys).containsExactlyInAnyOrder("GENERAL", "DATE", "PEOPLE", "LINKS")
        assertThat(fields["GENERAL"]).containsExactly("summary", "status", "cf_severity")
        assertThat(fields["PEOPLE"]).containsExactly("assignee", "reporter")
    }

    @Test
    fun `한 그룹을 교체해도 다른 그룹은 남는다`() {
        val board = insertBoard()
        settingsRepository.replaceDetailViewFields(board.id, "GENERAL", listOf("summary", "status"))

        settingsRepository.replaceDetailViewFields(board.id, "PEOPLE", listOf("assignee"))

        val fields = settingsRepository.findDetailViewFields(board.id)
        assertThat(fields["GENERAL"]).containsExactly("summary", "status")
        assertThat(fields["PEOPLE"]).containsExactly("assignee")
    }

    @Test
    fun `상세 필드는 행이 뒤섞여 들어가 있어도 position 순으로 읽는다`() {
        val board = insertBoard()
        insertDetailViewRow(board.id, "GENERAL", 2, "third")
        insertDetailViewRow(board.id, "GENERAL", 0, "first")
        insertDetailViewRow(board.id, "GENERAL", 1, "second")

        val fields = readWithSeqScan { settingsRepository.findDetailViewFields(board.id) }

        assertThat(fields["GENERAL"]).containsExactly("first", "second", "third")
    }

    @Test
    fun `상세 필드는 보드별로 격리되고 카드 레이아웃 상한을 물려받지 않는다`() {
        // position 상한 0..2 는 카드(J17)의 제약이다 — 상세 보기에는 상한이 없다(V509 ④ 주석).
        val mine = insertBoard()
        val other = insertBoard()
        settingsRepository.replaceDetailViewFields(other.id, "GENERAL", listOf("summary"))

        settingsRepository.replaceDetailViewFields(mine.id, "GENERAL", listOf("a", "b", "c", "d", "e"))

        assertThat(settingsRepository.findDetailViewFields(mine.id)["GENERAL"])
            .containsExactly("a", "b", "c", "d", "e")
        assertThat(settingsRepository.findDetailViewFields(other.id)["GENERAL"]).containsExactly("summary")
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    /**
     * 인덱스 스캔을 끈 트랜잭션 안에서 [block] 을 실행한다 — **정렬 판정을 공허하지 않게 만드는 장치**다.
     *
     * ★실측(뮤테이션 M3)에서 `findCardLayout` 의 `ORDER BY` 를 통째로 지워도 **19개가 전부 초록**이었다.
     * `WHERE board_id = ?` 가 PK 인덱스 `(board_id, view_scope, position)` 를 타고, 그 인덱스 순서가
     * 곧 `position` 순이라 정렬 없이도 정렬된 결과가 나왔기 때문이다. 그대로 두면 「position 순으로
     * 읽는다」는 판정이 **구현이 아니라 실행 계획**을 재고 있는 것이다.
     *
     * seq 스캔을 강제하면 일부러 뒤섞어 심은 힙 순서(2·0·1)가 그대로 나오므로 `ORDER BY` 의 유무가
     * 결과를 가른다. `SET LOCAL` 이라 트랜잭션이 끝나면 세션 설정이 원복돼 다른 테스트에 새지 않는다.
     */
    private fun <T> readWithSeqScan(block: () -> T): T =
        requireNotNull(
            TransactionTemplate(transactionManager).execute {
                dsl.setLocal(DSL.name("enable_indexscan"), DSL.value("off")).execute()
                dsl.setLocal(DSL.name("enable_bitmapscan"), DSL.value("off")).execute()
                block()
            },
        ) { "readWithSeqScan 블록이 null 을 돌려줬다 — 트랜잭션이 열리지 않았을 수 있다." }

    /** board_card_layout_fields 원본 행 — 읽기 경로를 거치지 않고 저장된 것을 직접 본다. */
    private fun rawCardLayout(
        boardId: UUID,
        viewScope: String,
    ): Map<Short?, String?> =
        dsl.select(BOARD_CARD_LAYOUT_FIELDS.POSITION, BOARD_CARD_LAYOUT_FIELDS.FIELD_KEY)
            .from(BOARD_CARD_LAYOUT_FIELDS)
            .where(BOARD_CARD_LAYOUT_FIELDS.BOARD_ID.eq(boardId))
            .and(BOARD_CARD_LAYOUT_FIELDS.VIEW_SCOPE.eq(viewScope))
            .fetchMap(BOARD_CARD_LAYOUT_FIELDS.POSITION, BOARD_CARD_LAYOUT_FIELDS.FIELD_KEY)

    private fun insertCardLayoutRow(
        boardId: UUID,
        viewScope: String,
        position: Int,
        fieldKey: String,
    ) {
        dsl.insertInto(BOARD_CARD_LAYOUT_FIELDS)
            .set(BOARD_CARD_LAYOUT_FIELDS.BOARD_ID, boardId)
            .set(BOARD_CARD_LAYOUT_FIELDS.VIEW_SCOPE, viewScope)
            .set(BOARD_CARD_LAYOUT_FIELDS.POSITION, position.toShort())
            .set(BOARD_CARD_LAYOUT_FIELDS.FIELD_KEY, fieldKey)
            .execute()
    }

    private fun insertDetailViewRow(
        boardId: UUID,
        fieldGroup: String,
        position: Int,
        fieldKey: String,
    ) {
        dsl.insertInto(BOARD_DETAIL_VIEW_FIELDS)
            .set(BOARD_DETAIL_VIEW_FIELDS.BOARD_ID, boardId)
            .set(BOARD_DETAIL_VIEW_FIELDS.FIELD_GROUP, fieldGroup)
            .set(BOARD_DETAIL_VIEW_FIELDS.POSITION, position.toShort())
            .set(BOARD_DETAIL_VIEW_FIELDS.FIELD_KEY, fieldKey)
            .execute()
    }

    /** [block] 이 던진 SQLSTATE. 죽지 **않으면** null 이라 「통과해 버렸다」가 그대로 드러난다. */
    private fun sqlStateOf(block: () -> Unit): String? {
        val thrown = runCatching(block).exceptionOrNull() ?: return null
        return generateSequence(thrown) { it.cause }
            .filterIsInstance<SQLException>()
            .firstOrNull()
            ?.sqlState
    }

    private fun insertBoard(): Board =
        boardRepository.insert(
            Board(
                id = UUID.randomUUID(),
                projectKey = "BST${UUID.randomUUID().toString().take(4).uppercase()}",
                name = "보드 설정 테스트 보드",
                columns = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )
}

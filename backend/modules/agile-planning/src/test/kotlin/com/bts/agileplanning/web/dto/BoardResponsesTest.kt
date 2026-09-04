// BoardColumnWithCardsResponse.wipLimit/wipExceeded + BoardDetailResponse.swimlaneField DTO 변환 단위테스트

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.application.BoardPlacementResult
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.BoardType
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.SwimlaneField
import com.bts.shared.board.BoardIssueView
import com.bts.shared.workflow.WorkflowStateView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.full.functions

/**
 * BoardColumnWithCardsResponse.from + BoardDetailResponse.of + BoardCardResponse.from DTO 변환 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 순수 변환 로직만 검증한다.
 *
 * 검증 시나리오.
 * - (a) wipLimit echo — placed.column.wipLimit 값 그대로 반영
 * - (b) wipExceeded 경계값 — 초과/미만/같음/null/카드0건 케이스
 * - (c) swimlaneField — board.swimlaneField.name 을 문자열로 노출
 * - (d) priority 노출 — BoardCardResponse.priority == BoardIssueView.priority (FR-BD-03 D6 스윔레인 근거)
 * - (e) 기존 필드 보존 — 신규 필드 추가 후 기존 필드 비파괴 확인
 * - (f) quickFilters 노출 — BoardDetailResponse.of 가 QuickFilter 도메인 목록을 QuickFilterResponse 로 변환(FR-UX-01 Task 7)
 */
class BoardResponsesTest {
    // --- 픽스처 헬퍼 ---

    /**
     * 이 클래스의 판정은 wip · 스윔레인 · priority 이지 **상태 이름이 아니다.** 컬럼 상태의
     * 표시 정보(R11)를 지는 판정은 [BoardColumnStatesResponseTest] 가 따로 가져갔으므로
     * 여기서는 빈 카탈로그로 변환한다 — 그러면 `states` 는 키를 이름으로 쓴다.
     */
    private val noCatalog = emptyMap<String, WorkflowStateView>()

    private val noStates = emptyList<WorkflowStateView>()

    private fun withCards(placed: PlacedColumn) = BoardColumnWithCardsResponse.from(placed, noCatalog)

    private fun column(wipLimit: Int? = null) =
        BoardColumn(
            id = UUID.randomUUID(),
            stateKeys = listOf("open"),
            name = "열림",
            category = "TODO",
            displayOrder = 1,
            wipLimit = wipLimit,
        )

    private fun card(
        key: String = "PROJ-1",
        epicKey: String? = null,
        typeKey: String = "task",
    ) = BoardIssueView(
        key = key,
        summary = "테스트 이슈",
        currentStateKey = "open",
        assigneeId = null,
        priority = 1,
        version = 0L,
        typeKey = typeKey,
        epicKey = epicKey,
    )

    private fun cards(count: Int): List<BoardIssueView> = (1..count).map { card("PROJ-$it") }

    private fun placed(
        wipLimit: Int? = null,
        cardCount: Int = 0,
    ): PlacedColumn = PlacedColumn(column = column(wipLimit), cards = cards(cardCount))

    private fun board(
        swimlaneField: SwimlaneField = SwimlaneField.NONE,
        boardType: BoardType = BoardType.KANBAN,
    ) = Board(
        id = UUID.randomUUID(),
        projectKey = "PROJ",
        name = "테스트 보드",
        columns = emptyList(),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        swimlaneField = swimlaneField,
        boardType = boardType,
    )

    private fun placementResult(
        columns: List<PlacedColumn> = emptyList(),
        activeSprint: Sprint? = null,
    ) = BoardPlacementResult(
        columns = columns,
        truncated = false,
        unplacedCount = 0,
        activeSprint = activeSprint,
    )

    /** 날짜 두 필드를 서로 다르게 둔다 — 매핑이 뒤바뀌면 드러나야 한다. */
    private fun sprint(
        name: String = "Sprint 3",
        startDate: LocalDate? = LocalDate.of(2026, 9, 1),
        endDate: LocalDate? = LocalDate.of(2026, 9, 14),
    ) = Sprint(
        id = UUID.randomUUID(),
        projectKey = "PROJ",
        boardId = UUID.randomUUID(),
        name = name,
        goal = null,
        status = SprintStatus.ACTIVE,
        startDate = startDate,
        endDate = endDate,
        version = 0L,
    )

    private fun quickFilter(
        name: String = "내 버그",
        query: String = "label=bug",
        boardId: UUID = UUID.randomUUID(),
    ) = QuickFilter(id = UUID.randomUUID(), boardId = boardId, name = name, query = query)

    // --- (g) FR-BD-04 신규 필드 — boardType · activeSprint ---
    //
    // ★ 이 절이 없을 때는 ActiveSprintResponse.from 에서 startDate 와 endDate 를 서로 바꿔 넣어도,
    // BoardDetailResponse 의 boardType 을 "KANBAN" 으로 하드코딩해도 전 스위트가 초록이었다(리뷰 지적).

    @Nested
    inner class BoardTypeExposure {
        @Test
        fun `board 가 SCRUM 이면 응답 boardType 은 문자열 SCRUM 이다`() {
            val response = BoardDetailResponse.of(board(boardType = BoardType.SCRUM), placementResult())
            assertThat(response.boardType).isEqualTo("SCRUM")
        }

        @Test
        fun `board 가 KANBAN 이면 응답 boardType 은 문자열 KANBAN 이다`() {
            val response = BoardDetailResponse.of(board(boardType = BoardType.KANBAN), placementResult())
            assertThat(response.boardType).isEqualTo("KANBAN")
        }

        @Test
        fun `생성 응답도 board 의 종류를 그대로 노출한다`() {
            assertThat(BoardResponse.from(board(boardType = BoardType.SCRUM), noStates).boardType)
                .isEqualTo("SCRUM")
            assertThat(BoardResponse.from(board(boardType = BoardType.KANBAN), noStates).boardType)
                .isEqualTo("KANBAN")
        }

        /**
         * PATCH 응답도 종류를 싣는다 (FR-BD-04 PR ⑤-3).
         *
         * 종류를 노출하는 DTO 가 5개인데 `BoardMetaResponse`(PATCH 응답) **하나만** 빠져 있었다.
         * 종류가 불변(ADR 편차 X3)이라 오늘 버그는 아니지만, **한 개념을 5곳 중 4곳만 싣는 계약**은
         * 소비자가 「PATCH 응답으로는 종류를 알 수 없다」를 학습하게 만들고 그 예외가 다음 결함이 된다.
         */
        @Test
        fun `PATCH 메타 응답도 board 의 종류를 그대로 노출한다`() {
            assertThat(BoardMetaResponse.from(board(boardType = BoardType.SCRUM)).boardType)
                .isEqualTo("SCRUM")
            assertThat(BoardMetaResponse.from(board(boardType = BoardType.KANBAN)).boardType)
                .isEqualTo("KANBAN")
        }
    }

    @Nested
    inner class ActiveSprintExposure {
        @Test
        fun `활성 스프린트가 있으면 id 이름 시작일 종료일이 그대로 실린다`() {
            val active = sprint(name = "Sprint 3")

            val response =
                BoardDetailResponse.of(
                    board(boardType = BoardType.SCRUM),
                    placementResult(activeSprint = active),
                )

            assertThat(response.activeSprint).isNotNull()
            assertThat(response.activeSprint!!.sprintId).isEqualTo(active.id)
            assertThat(response.activeSprint!!.name).isEqualTo("Sprint 3")
            // ★ 두 날짜를 각각 단언한다 — 하나만 보면 매핑이 뒤바뀌어도 통과한다.
            assertThat(response.activeSprint!!.startDate).isEqualTo(LocalDate.of(2026, 9, 1))
            assertThat(response.activeSprint!!.endDate).isEqualTo(LocalDate.of(2026, 9, 14))
        }

        @Test
        fun `기간 미설정 스프린트는 두 날짜가 모두 null 이다`() {
            val active = sprint(startDate = null, endDate = null)

            val response =
                BoardDetailResponse.of(
                    board(boardType = BoardType.SCRUM),
                    placementResult(activeSprint = active),
                )

            assertThat(response.activeSprint!!.startDate).isNull()
            assertThat(response.activeSprint!!.endDate).isNull()
        }

        @Test
        fun `활성 스프린트가 없으면 activeSprint 는 null 이다`() {
            val response = BoardDetailResponse.of(board(boardType = BoardType.SCRUM), placementResult())
            assertThat(response.activeSprint).isNull()
        }
    }

    // --- (a) wipLimit echo ---

    @Nested
    inner class WipLimitEcho {
        @Test
        fun `wipLimit null 인 컬럼은 응답 wipLimit 도 null 이다`() {
            val response = withCards(placed(wipLimit = null))
            assertThat(response.wipLimit).isNull()
        }

        @Test
        fun `wipLimit 3 인 컬럼은 응답 wipLimit 도 3 이다`() {
            val response = withCards(placed(wipLimit = 3))
            assertThat(response.wipLimit).isEqualTo(3)
        }

        @Test
        fun `wipLimit 1 인 컬럼은 응답 wipLimit 도 1 이다`() {
            val response = withCards(placed(wipLimit = 1))
            assertThat(response.wipLimit).isEqualTo(1)
        }
    }

    // --- (b) wipExceeded 경계값 ---

    @Nested
    inner class WipExceeded {
        @Test
        fun `wipLimit 3 카드 5건이면 wipExceeded true 다`() {
            val response = withCards(placed(wipLimit = 3, cardCount = 5))
            assertThat(response.wipExceeded).isTrue()
        }

        @Test
        fun `wipLimit 3 카드 2건이면 wipExceeded false 다`() {
            val response = withCards(placed(wipLimit = 3, cardCount = 2))
            assertThat(response.wipExceeded).isFalse()
        }

        @Test
        fun `wipLimit 3 카드 3건이면 같으므로 wipExceeded false 다`() {
            val response = withCards(placed(wipLimit = 3, cardCount = 3))
            assertThat(response.wipExceeded).isFalse()
        }

        @Test
        fun `wipLimit null 이면 카드가 많아도 wipExceeded false 다`() {
            val response = withCards(placed(wipLimit = null, cardCount = 100))
            assertThat(response.wipExceeded).isFalse()
        }

        @Test
        fun `카드 0건이면 wipExceeded false 다`() {
            val response = withCards(placed(wipLimit = 3, cardCount = 0))
            assertThat(response.wipExceeded).isFalse()
        }

        @Test
        fun `wipLimit null 카드 0건이면 wipExceeded false 다`() {
            val response = withCards(placed(wipLimit = null, cardCount = 0))
            assertThat(response.wipExceeded).isFalse()
        }
    }

    // --- (c) swimlaneField 노출 ---

    @Nested
    inner class SwimlaneFieldExposure {
        @Test
        fun `board swimlaneField NONE 이면 응답 swimlaneField 는 문자열 NONE 이다`() {
            val response = BoardDetailResponse.of(board(SwimlaneField.NONE), placementResult())
            assertThat(response.swimlaneField).isEqualTo("NONE")
        }

        @Test
        fun `board swimlaneField ASSIGNEE 이면 응답 swimlaneField 는 문자열 ASSIGNEE 이다`() {
            val response = BoardDetailResponse.of(board(SwimlaneField.ASSIGNEE), placementResult())
            assertThat(response.swimlaneField).isEqualTo("ASSIGNEE")
        }

        @Test
        fun `board swimlaneField PRIORITY 이면 응답 swimlaneField 는 문자열 PRIORITY 이다`() {
            val response = BoardDetailResponse.of(board(SwimlaneField.PRIORITY), placementResult())
            assertThat(response.swimlaneField).isEqualTo("PRIORITY")
        }
    }

    // --- (d) BoardCardResponse.priority 노출 ---

    @Nested
    inner class BoardCardResponsePriority {
        @Test
        fun `BoardCardResponse 는 BoardIssueView 의 priority 를 그대로 노출한다`() {
            val view = card("PROJ-1")
            val response = BoardCardResponse.from(view)
            assertThat(response.priority).isEqualTo(view.priority)
        }

        @Test
        fun `priority 0 인 카드는 응답 priority 도 0 이다`() {
            val view =
                BoardIssueView(
                    key = "PROJ-2",
                    summary = "우선순위 최상",
                    currentStateKey = "open",
                    assigneeId = null,
                    priority = 0,
                    version = 0L,
                    typeKey = "task",
                )
            val response = BoardCardResponse.from(view)
            assertThat(response.priority).isEqualTo(0)
        }

        @Test
        fun `priority 99 인 카드는 응답 priority 도 99 이다`() {
            val view =
                BoardIssueView(
                    key = "PROJ-3",
                    summary = "우선순위 최하",
                    currentStateKey = "open",
                    assigneeId = null,
                    priority = 99,
                    version = 0L,
                    typeKey = "task",
                )
            val response = BoardCardResponse.from(view)
            assertThat(response.priority).isEqualTo(99)
        }
    }

    // --- (e-1) BoardCardResponse.epicKey 노출 (FR-EP-01 D6/D7 EPIC 스윔레인 근거) ---

    @Nested
    inner class BoardCardResponseEpicKey {
        @Test
        fun `BoardCardResponse 는 BoardIssueView 의 epicKey 를 그대로 노출한다`() {
            val view = card("PROJ-1", epicKey = "PROJ-0")
            val response = BoardCardResponse.from(view)
            assertThat(response.epicKey).isEqualTo("PROJ-0")
        }

        @Test
        fun `epicKey 가 null 인 카드는 응답 epicKey 도 null 이다`() {
            val view = card("PROJ-1", epicKey = null)
            val response = BoardCardResponse.from(view)
            assertThat(response.epicKey).isNull()
        }
    }

    // --- (e) 기존 필드 보존 ---

    @Nested
    inner class BoardCardResponseDensityFields {
        // FR-UX-14 B2. 헬퍼 card() 를 쓰지 않고 BoardIssueView 를 직접 만든다 —
        // 헬퍼는 typeKey 기본값을 채우므로 3필드의 증인이 될 수 없다(스펙 §9.3 GAP-2).

        @Test
        fun `from 은 BoardIssueView 의 유형 라벨 추정을 그대로 나른다`() {
            val view =
                BoardIssueView(
                    key = "PROJ-1",
                    summary = "제목",
                    currentStateKey = "open",
                    assigneeId = null,
                    priority = 1,
                    version = 0L,
                    typeKey = "bug",
                    labels = listOf("urgent", "api"),
                    originalEstimateSeconds = 3600,
                )

            val response = BoardCardResponse.from(view)

            assertThat(response.typeKey).isEqualTo("bug")
            assertThat(response.labels).containsExactly("urgent", "api")
            assertThat(response.originalEstimateSeconds).isEqualTo(3600)
        }

        @Test
        fun `from 은 라벨 없는 뷰를 빈 배열로 추정 없는 뷰를 null 로 나른다`() {
            val view =
                BoardIssueView(
                    key = "PROJ-2",
                    summary = "제목",
                    currentStateKey = "open",
                    assigneeId = null,
                    priority = 1,
                    version = 0L,
                    typeKey = "task",
                )

            val response = BoardCardResponse.from(view)

            assertThat(response.typeKey).isEqualTo("task")
            assertThat(response.labels).isEmpty()
            assertThat(response.originalEstimateSeconds).isNull()
        }

        @Test
        fun `서로 다른 유형의 뷰는 각자 자기 유형을 갖는다`() {
            // 대조군 — 단일 유형만 검사하면 from 이 상수를 반환해도 통과한다(스펙 §9.3 GAP-1).
            val bug = BoardCardResponse.from(cardWithType("PROJ-3", "bug"))
            val story = BoardCardResponse.from(cardWithType("PROJ-4", "story"))

            assertThat(bug.typeKey).isEqualTo("bug")
            assertThat(story.typeKey).isEqualTo("story")
        }

        private fun cardWithType(
            key: String,
            typeKey: String,
        ) = BoardIssueView(
            key = key,
            summary = "제목 $key",
            currentStateKey = "open",
            assigneeId = null,
            priority = 1,
            version = 0L,
            typeKey = typeKey,
        )
    }

    @Nested
    inner class ExistingFieldsPreserved {
        @Test
        fun `BoardColumnWithCardsResponse 기존 필드가 신규 필드 추가 후에도 유지된다`() {
            val col = column(wipLimit = 2)
            val cardList = cards(1)
            val placedColumn = PlacedColumn(column = col, cards = cardList)
            val response = withCards(placedColumn)

            assertThat(response.columnId).isEqualTo(col.id)
            // 1:1 시절의 `stateKey` 자리다. 이제 배열이고, 컬럼이 담은 키를 순서대로 낸다(R11).
            assertThat(response.states.map { it.key }).isEqualTo(col.stateKeys)
            assertThat(response.name).isEqualTo(col.name)
            assertThat(response.category).isEqualTo(col.category)
            assertThat(response.displayOrder).isEqualTo(col.displayOrder)
            assertThat(response.cards).hasSize(1)
        }

        @Test
        fun `BoardDetailResponse 기존 필드가 신규 필드 추가 후에도 유지된다`() {
            val b = board()
            val result = placementResult()
            val response = BoardDetailResponse.of(b, result)

            assertThat(response.boardId).isEqualTo(b.id)
            assertThat(response.projectKey).isEqualTo(b.projectKey)
            assertThat(response.name).isEqualTo(b.name)
            assertThat(response.truncated).isFalse()
            assertThat(response.unplacedCount).isEqualTo(0)
        }

        /**
         * 목록 DTO 의 기존 4필드는 canDelete 추가 뒤에도 그대로여야 한다.
         *
         * 보드 스위처 · 백로그 헤더 · 탭바가 이 목록 하나만 읽는다 — 필드가 하나 사라지면
         * 세 화면이 함께 죽는다.
         */
        @Test
        fun `BoardSummaryResponse 기존 4필드가 canDelete 추가 후에도 유지된다`() {
            val b = board(boardType = BoardType.SCRUM)

            val response = BoardSummaryResponse.from(b, canDelete = true)

            assertThat(response.boardId).isEqualTo(b.id)
            assertThat(response.projectKey).isEqualTo(b.projectKey)
            assertThat(response.name).isEqualTo(b.name)
            assertThat(response.boardType).isEqualTo("SCRUM")
        }

        @Test
        fun `BoardSummaryResponse from 은 canDelete 인자를 그대로 싣는다`() {
            // 한쪽만 단언하면 상수 하드코딩(항상 true 또는 항상 false)이 그대로 통과한다.
            assertThat(BoardSummaryResponse.from(board(), canDelete = true).canDelete).isTrue()
            assertThat(BoardSummaryResponse.from(board(), canDelete = false).canDelete).isFalse()
        }

        /**
         * `canDelete` 파라미터에 기본값이 있으면 안 된다.
         *
         * 기본값을 주는 순간 컨트롤러의 `.map(BoardSummaryResponse::from)` 이 **고치지 않아도 그대로
         * 컴파일되고**, 응답이 전량 false 로 나가는데 컴파일러도 detekt 도 침묵한다. 기본값 부재만이
         * 그 실수를 컴파일 에러로 만든다. 그러니 이 성질은 사람 눈이 아니라 기계가 지킨다.
         */
        @Test
        fun `BoardSummaryResponse from 의 canDelete 파라미터에는 기본값이 없다`() {
            val fromFn = BoardSummaryResponse.Companion::class.functions.single { it.name == "from" }

            val canDeleteParam = fromFn.parameters.single { it.name == "canDelete" }

            assertThat(canDeleteParam.isOptional).isFalse()
        }
    }

    // --- (f) quickFilters 노출 (FR-UX-01 Task 7) ---

    @Nested
    inner class QuickFiltersExposure {
        @Test
        fun `BoardDetailResponse of 는 QuickFilter 도메인 목록을 QuickFilterResponse 로 변환해 created_at 순서를 보존한다`() {
            val boardId = UUID.randomUUID()
            val filters =
                listOf(
                    quickFilter(name = "내 버그", query = "label=bug", boardId = boardId),
                    quickFilter(name = "긴급", query = "label=urgent", boardId = boardId),
                )

            val response = BoardDetailResponse.of(board(), placementResult(), filters)

            assertThat(response.quickFilters).hasSize(2)
            assertThat(response.quickFilters.map { it.name }).containsExactly("내 버그", "긴급")
            assertThat(response.quickFilters[0].filterId).isEqualTo(filters[0].id)
            assertThat(response.quickFilters[0].query).isEqualTo(filters[0].query)
            assertThat(response.quickFilters[1].filterId).isEqualTo(filters[1].id)
        }

        @Test
        fun `quickFilters 가 빈 목록이면 응답 quickFilters 도 빈 목록이다`() {
            val response = BoardDetailResponse.of(board(), placementResult(), emptyList())
            assertThat(response.quickFilters).isEmpty()
        }

        @Test
        fun `quickFilters 인자 없이 호출하면 기본값으로 빈 목록이 반환된다`() {
            val response = BoardDetailResponse.of(board(), placementResult())
            assertThat(response.quickFilters).isEmpty()
        }
    }
}

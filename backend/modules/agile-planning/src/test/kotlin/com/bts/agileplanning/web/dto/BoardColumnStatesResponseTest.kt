// 컬럼 상태 배열(states) + 미매핑 상태 목록(unmappedStates) 응답 계약 단위테스트 — R8·R11

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.application.BoardPlacementResult
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.shared.workflow.WorkflowStateView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 응답 DTO 3종의 `states` 배열(R11)과 보드 조회의 `unmappedStates`(R8) 계약.
 *
 * ## 왜 별도 파일인가
 * [BoardResponsesTest] 는 이미 487줄이다. `DEVELOPMENT.md §2.1` 의 파일 상한을 더 밀지 않는다.
 * 여기 모인 판정은 「컬럼이 상태 여럿을 담는다」는 한 관심사에 응집한다.
 *
 * ## 이 클래스가 지는 판정
 *
 * | 축 | 무엇 | 근거 |
 * |---|---|---|
 * | ① 3종 동형 | `BoardColumnResponse` · `BoardColumnWithCardsResponse` · `ColumnMetaResponse` 가 같은 `states` 를 낸다 | R11 |
 * | ② 상태별 category | 컬럼 `category`(R5 최댓값)와 별개로 상태 각자의 `category` 가 실린다 | R11 · **R13** |
 * | ③ 카탈로그 결손 | 워크플로우에서 지워진 상태 키도 드롭하지 않는다 | E2 반대편 |
 * | ④ 미매핑 목록 | 어느 컬럼에도 없는 상태를 `unmappedStates` 로 낸다 | R8 · J2 |
 *
 * ★ ② 가 이 PR 의 무게중심이다. 프론트 `board-drop.ts` 가 오늘 **컬럼** `category` 로 해결 방안
 * 모달을 분기하는데, 지라와 BTS 백엔드는 **대상 상태**로 판단한다. 상태별 `category` 가 응답에
 * 실려야 R13(Task 7)이 그 분기를 옮길 수 있다 — 키 배열만 주면 프론트가 별도 조회를 해야 한다.
 *
 * 외부 의존(DB · Spring · mock) 없이 순수 변환만 검증한다.
 */
class BoardColumnStatesResponseTest {
    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private fun view(
        key: String,
        name: String,
        category: String,
    ) = WorkflowStateView(key = key, name = name, isDone = category == "DONE", category = category)

    /**
     * 컬럼이 담은 상태의 `name`·`category` 는 도메인에 없다 — 워크플로우 카탈로그에서만 온다.
     * DTO 가 그 카탈로그를 **인자로** 받고 기본값을 주지 않는 것이 의도다. 기본값이 있으면
     * 카탈로그를 안 넘긴 호출부가 「키를 이름으로 쓴 응답」을 조용히 내보낸다.
     */
    private fun catalogOf(vararg views: WorkflowStateView) = ColumnStateResponse.catalog(views.toList())

    private fun col(
        vararg stateKeys: String,
        name: String = "컬럼",
        category: String = "IN_PROGRESS",
    ) = BoardColumn(
        id = UUID.randomUUID(),
        stateKeys = stateKeys.toList(),
        name = name,
        category = category,
        displayOrder = 1,
    )

    private fun board() =
        Board(
            id = UUID.randomUUID(),
            projectKey = "PROJ",
            name = "테스트 보드",
            columns = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun placementResult(
        columns: List<PlacedColumn> = emptyList(),
        stateCatalog: List<WorkflowStateView> = emptyList(),
    ) = BoardPlacementResult(
        columns = columns,
        truncated = false,
        unplacedCount = 0,
        stateCatalog = stateCatalog,
    )

    // ── ① 3종 동형 (R11) ──────────────────────────────────────────────────────

    @Nested
    inner class ColumnStatesExposure {
        @Test
        fun `BoardColumnResponse 가 states 배열을 낸다 key name category 포함`() {
            val column = col("in-progress", "in-review")
            val catalog =
                catalogOf(
                    view("in-progress", "진행 중", "IN_PROGRESS"),
                    view("in-review", "검토 중", "IN_PROGRESS"),
                )

            val response = BoardColumnResponse.from(column, catalog)

            assertThat(response.states.map { it.key }).containsExactly("in-progress", "in-review")
            assertThat(response.states.map { it.name }).containsExactly("진행 중", "검토 중")
            assertThat(response.states.map { it.category }).containsExactly("IN_PROGRESS", "IN_PROGRESS")
        }

        @Test
        fun `BoardColumnWithCardsResponse 와 ColumnMetaResponse 도 같은 형태를 낸다`() {
            // 세 DTO 가 같은 모양이어야 프론트가 스키마 하나로 파싱한다(R11).
            val column = col("in-progress", "in-review")
            val catalog =
                catalogOf(
                    view("in-progress", "진행 중", "IN_PROGRESS"),
                    view("in-review", "검토 중", "IN_PROGRESS"),
                )

            val withCards =
                BoardColumnWithCardsResponse.from(PlacedColumn(column = column, cards = emptyList()), catalog)
            val meta = ColumnMetaResponse.from(column, catalog)

            assertThat(withCards.states).isEqualTo(meta.states)
            assertThat(meta.states.map { it.key }).containsExactly("in-progress", "in-review")
            assertThat(meta.states.map { it.name }).containsExactly("진행 중", "검토 중")
        }

        @Test
        fun `상태 0개 컬럼은 states 가 빈 배열이다`() {
            val column = col(name = "빈 컬럼", category = "TODO")

            assertThat(BoardColumnResponse.from(column, catalogOf()).states).isEmpty()
        }
    }

    // ── ② 상태별 category (R11 · R13) ─────────────────────────────────────────

    @Nested
    inner class PerStateCategory {
        @Test
        fun `대상 상태의 category 가 컬럼 category 와 달라도 상태의 것을 낸다`() {
            // 컬럼 category 는 R5 의 최댓값(DONE)이라 개별 상태와 다를 수 있다.
            // R13 이 「대상 상태」로 해결 방안 모달을 판정하려면 이 구분이 응답에 남아야 한다.
            val column = col("in-review", "closed", name = "마무리", category = "DONE")
            val catalog = catalogOf(view("in-review", "검토 중", "IN_PROGRESS"), view("closed", "완료", "DONE"))

            val response = ColumnMetaResponse.from(column, catalog)

            assertThat(response.category).isEqualTo("DONE")
            assertThat(response.states.map { it.category }).containsExactly("IN_PROGRESS", "DONE")
        }
    }

    // ── ③ 카탈로그 결손 ───────────────────────────────────────────────────────

    @Nested
    inner class CatalogMiss {
        @Test
        fun `카탈로그에 없는 상태 키도 사라지지 않고 키를 이름으로 쓴다`() {
            // 워크플로우에서 상태가 지워졌는데 매핑이 남은 창. 드롭하면 컬럼이 조용히 비어 보인다.
            val column = col("ghost", name = "유령", category = "TODO")

            val response = BoardColumnResponse.from(column, catalogOf())

            assertThat(response.states)
                .containsExactly(ColumnStateResponse(key = "ghost", name = "ghost", category = "TODO"))
        }
    }

    // ── ④ 미매핑 목록 (R8 · J2) ───────────────────────────────────────────────

    @Nested
    inner class UnmappedStatesExposure {
        @Test
        fun `BoardDetailResponse 가 어느 컬럼에도 없는 상태를 unmappedStates 로 낸다`() {
            val result =
                placementResult(
                    columns = listOf(PlacedColumn(column = col("open"), cards = emptyList())),
                    stateCatalog = listOf(view("open", "열림", "TODO"), view("blocked", "차단됨", "TODO")),
                )

            val response = BoardDetailResponse.of(board(), result)

            assertThat(response.unmappedStates)
                .containsExactly(ColumnStateResponse(key = "blocked", name = "차단됨", category = "TODO"))
        }

        @Test
        fun `모든 상태가 매핑됐으면 unmappedStates 가 빈 배열이다`() {
            val result =
                placementResult(
                    columns = listOf(PlacedColumn(column = col("open"), cards = emptyList())),
                    stateCatalog = listOf(view("open", "열림", "TODO")),
                )

            assertThat(BoardDetailResponse.of(board(), result).unmappedStates).isEmpty()
        }

        @Test
        fun `미매핑 목록은 카탈로그 순서를 보존한다`() {
            val result =
                placementResult(
                    columns = listOf(PlacedColumn(column = col("open"), cards = emptyList())),
                    stateCatalog =
                        listOf(
                            view("blocked", "차단됨", "TODO"),
                            view("open", "열림", "TODO"),
                            view("archived", "보관됨", "DONE"),
                        ),
                )

            assertThat(BoardDetailResponse.of(board(), result).unmappedStates.map { it.key })
                .containsExactly("blocked", "archived")
        }
    }
}

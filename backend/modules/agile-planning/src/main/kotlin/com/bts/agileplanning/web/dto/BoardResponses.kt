// 보드 REST API 요청/응답 DTO + DataResponse 봉투 — agile-planning BC (FR-BD-01, FR-BD-03)

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.application.BoardCardMoveResult
import com.bts.agileplanning.application.BoardPlacementResult
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.domain.Sprint
import com.bts.shared.board.BoardIssueView
import com.bts.shared.workflow.WorkflowStateView
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import org.openapitools.jackson.nullable.JsonNullable
import java.time.LocalDate
import java.util.UUID

/**
 * 성공 응답 래퍼.
 *
 * issue-tracking `com.bts.issue.adapter.inbound.rest.DataResponse` 와 동일한 봉투 형태를
 * BC 격리 원칙에 따라 agile-planning 이 독자적으로 정의한다(다른 BC 의 타입을 직접 import 하지 않는다).
 *
 * @param T 응답 데이터 타입.
 * @property data 응답 페이로드.
 */
data class DataResponse<T>(val data: T)

/**
 * 보드 생성 요청 바디.
 *
 * @property projectKey 보드를 생성할 프로젝트 키. 예: `"BTS"`. 공백 불가.
 * @property name 보드 표시 이름. 공백 불가.
 * @property boardType 보드 종류(`"SCRUM"`/`"KANBAN"`). **선택** — 생략하면 `KANBAN` 이다.
 *   Bean Validation 으로 값 집합을 강제하지 않는다. 파싱을
 *   [com.bts.agileplanning.domain.BoardType.from] 한 곳에 모아 허용값이 늘 때 두 자리를 고치지
 *   않게 하고, 위반은 named exception 으로 400 이 된다.
 */
data class CreateBoardRequest(
    @field:NotBlank
    val projectKey: String,
    @field:NotBlank
    val name: String,
    val boardType: String? = null,
)

/**
 * 카드 이동(전환) 요청 바디.
 *
 * actor 는 body 로 받지 않는다 — SecurityContext 에서 추출한다(actor 위조 차단).
 *
 * ## 대상은 상태로 지목한다 (R6 · J3 · J4)
 *
 * 지라는 컬럼 안의 **각 상태를 드롭존**으로 그린다 — 「컬럼으로 드롭」이라는 조작이 없다.
 * 그래서 [toStateKey] 가 정본 경로이고 [toColumnId] 는 하위 호환이다(R7).
 * **둘 중 정확히 하나**를 보내야 하며, 그 판정은 서비스가 진다 — `@NotNull` 로는
 * 「둘 중 하나」를 표현할 수 없고, 컨트롤러에도 두면 같은 규칙이 두 곳이 된다.
 *
 * @property toColumnId 하위 호환 — 이동 대상 컬럼 UUID. 그 컬럼의 상태가 2개 이상이면 400.
 * @property toStateKey 이동 대상 워크플로우 상태 키. 이 보드의 어느 컬럼에도 없으면 404.
 * @property expectedVersion 낙관적 락(OCC) 기대 버전. 0 이상 필수.
 * @property resolutionId DONE 카테고리 전환 시 필요한 해결 방안 ID. 불필요하면 null.
 */
data class MoveCardRequest(
    val toColumnId: UUID? = null,
    val toStateKey: String? = null,
    @field:NotNull
    @field:PositiveOrZero
    val expectedVersion: Long?,
    val resolutionId: UUID? = null,
)

/**
 * 보드 컬럼 응답 DTO(카드 미포함, 생성 응답용).
 *
 * @property columnId 컬럼 UUID.
 * @property states 이 컬럼에 매핑된 워크플로우 상태 목록(0개 이상 · `display_order` 순). R11.
 * @property name 컬럼 표시 이름.
 * @property category 칸반 카테고리. `"TODO"` · `"IN_PROGRESS"` · `"DONE"`.
 *   담은 상태들의 **최댓값**이고(R5) 개별 상태의 category 와 다를 수 있다 — 표시 전용이다.
 * @property displayOrder 컬럼 표시 순서(오름차순).
 */
data class BoardColumnResponse(
    val columnId: UUID,
    val states: List<ColumnStateResponse>,
    val name: String,
    val category: String,
    val displayOrder: Int,
) {
    companion object {
        /**
         * 도메인 [BoardColumn] 을 [BoardColumnResponse] 로 변환한다.
         *
         * @param catalog [ColumnStateResponse.catalog] 로 만든 상태 색인. **기본값을 두지 않는다** —
         *   두면 카탈로그를 안 넘긴 호출부가 「키를 이름으로 쓴 응답」을 조용히 내보낸다.
         */
        fun from(
            column: BoardColumn,
            catalog: Map<String, WorkflowStateView>,
        ): BoardColumnResponse =
            BoardColumnResponse(
                columnId = column.id,
                states = ColumnStateResponse.resolve(column.stateKeys, catalog),
                name = column.name,
                category = column.category,
                displayOrder = column.displayOrder,
            )
    }
}

/**
 * 보드 생성 응답 DTO.
 *
 * @property boardId 생성된 보드 UUID.
 * @property projectKey 소속 프로젝트 키.
 * @property name 보드 표시 이름.
 * @property columns 시드된 컬럼 목록(카드 미포함).
 * @property boardType 보드 종류(`"SCRUM"`/`"KANBAN"`). 생성 직후 클라이언트가 종류를 되읽는 유일한 자리다.
 */
data class BoardResponse(
    val boardId: UUID,
    val projectKey: String,
    val name: String,
    val columns: List<BoardColumnResponse>,
    val boardType: String,
) {
    companion object {
        /**
         * 도메인 [Board] 를 [BoardResponse](컬럼 포함, 카드 미포함) 로 변환한다.
         *
         * @param states 프로젝트의 워크플로우 상태 전량. 컬럼 상태의 이름·카테고리 출처(R11).
         */
        fun from(
            board: Board,
            states: List<WorkflowStateView>,
        ): BoardResponse {
            val catalog = ColumnStateResponse.catalog(states)
            return BoardResponse(
                boardId = board.id,
                projectKey = board.projectKey,
                name = board.name,
                columns = board.columns.map { BoardColumnResponse.from(it, catalog) },
                boardType = board.boardType.name,
            )
        }
    }
}

/**
 * 보드 목록 항목 응답 DTO(컬럼 미포함).
 *
 * @property boardId 보드 UUID.
 * @property projectKey 소속 프로젝트 키.
 * @property name 보드 표시 이름.
 * @property boardType 보드 종류(`"SCRUM"`/`"KANBAN"`). 보드 스위처가 종류를 표시하는 유일한 출처다 —
 *   스위처는 목록만 읽고 항목마다 상세를 조회하지 않으므로 이 필드가 없으면 종류를 알 방법이 없다(FR-BD-04).
 */
data class BoardSummaryResponse(
    val boardId: UUID,
    val projectKey: String,
    val name: String,
    val boardType: String,
) {
    companion object {
        /** 도메인 [Board] 를 [BoardSummaryResponse](메타만) 로 변환한다. */
        fun from(board: Board): BoardSummaryResponse =
            BoardSummaryResponse(
                boardId = board.id,
                projectKey = board.projectKey,
                name = board.name,
                boardType = board.boardType.name,
            )
    }
}

/**
 * 보드 카드(이슈) 응답 DTO.
 *
 * priority 는 PRIORITY 스윔레인(FR-BD-03 D6) 그룹화 근거로 재노출 — Maxi 확정.
 * epicKey 는 EPIC 스윔레인(FR-EP-01 D6/D7) 그룹화 근거로 재노출 — Maxi 확정.
 *
 * @property issueKey 이슈 키. 예: `"BTS-1"`.
 * @property summary 이슈 제목.
 * @property assigneeId 담당자 UUID. 미배정이면 null.
 * @property priority 우선순위 값. 숫자 작을수록 높은 우선순위(BoardIssueLookupPort 정합).
 * @property version 낙관적 락(OCC) 버전. 카드 이동 시 expectedVersion 으로 사용.
 * @property epicKey 이슈가 속한 에픽의 이슈 키. 에픽 없는 이슈는 null.
 *   동일 프로젝트 에픽만 포함 — cross-project 에픽은 null (P1-A 누출 방지).
 * @property typeKey 이슈 유형 키(`issue_types.key`). 소문자. 예: `"bug"` (FR-UX-14 B2).
 *   유형 **이름·아이콘은 담지 않는다** — 클라이언트가 기존 타입 목록 API 에서 얻는다.
 *   카드 1,000건에 같은 문자열을 반복해 싣지 않고 유형 메타의 출처를 한 곳으로 유지하기 위함이다(ADR §D-1).
 * @property labels 이슈 라벨 목록. 라벨이 없으면 **빈 배열**로 직렬화된다(null 아님) (FR-UX-14 B2).
 * @property originalEstimateSeconds 최초 추정 작업 시간(초). 미추정이면 null (FR-UX-14 B2).
 * @property rank LexoRank 정렬 키. **소유는 issue-tracking BC** — agile-planning 은 이 값을
 *   그대로 미러 노출할 뿐 생성·갱신하지 않는다(백로그 `BacklogResponses` 의 rank 노출 선례와
 *   동일한 방식). null 이면 미부여(정렬 시 NULLS LAST). 이 필드는 노출 전용이며 카드 정렬 순서는
 *   여전히 [PlacedColumn.cards] 가 결정한다(정렬 로직 자체의 변경은 별도 Task 소관).
 */
data class BoardCardResponse(
    val issueKey: String,
    val summary: String,
    val assigneeId: UUID?,
    val priority: Int,
    val version: Long,
    val typeKey: String,
    val epicKey: String? = null,
    val rank: String? = null,
    val labels: List<String> = emptyList(),
    val originalEstimateSeconds: Int? = null,
) {
    companion object {
        /** cross-BC [BoardIssueView] 를 [BoardCardResponse] 로 변환한다. */
        fun from(card: BoardIssueView): BoardCardResponse =
            BoardCardResponse(
                issueKey = card.key,
                summary = card.summary,
                assigneeId = card.assigneeId,
                priority = card.priority,
                version = card.version,
                typeKey = card.typeKey,
                epicKey = card.epicKey,
                rank = card.rank,
                labels = card.labels,
                originalEstimateSeconds = card.originalEstimateSeconds,
            )
    }
}

/**
 * 보드 단건 조회 컬럼 응답 DTO(카드 포함).
 *
 * @property columnId 컬럼 UUID.
 * @property states 이 컬럼에 매핑된 워크플로우 상태 목록(0개 이상 · `display_order` 순). R11.
 *   ★프론트가 카드를 떨굴 때 **대상 상태**의 `category` 로 해결 방안 모달을 판정한다(R13).
 * @property name 컬럼 표시 이름.
 * @property category 칸반 카테고리. 담은 상태들의 최댓값(R5)이라 개별 상태와 다를 수 있다.
 * @property displayOrder 컬럼 표시 순서.
 * @property cards 이 컬럼에 배치된 카드 목록(priority ASC 정렬).
 * @property wipLimit WIP(Work In Progress) 제한 수. null 이면 무제한.
 *   [com.bts.agileplanning.domain.BoardColumn.wipLimit] 값을 그대로 반영한다.
 * @property wipExceeded 현재 카드 수가 [wipLimit] 를 초과하면 true. [wipLimit] 가 null 이거나
 *   카드 수가 [wipLimit] 이하이면 false. 같을 때도 초과로 판단하지 않는다(strictly greater than).
 */
data class BoardColumnWithCardsResponse(
    val columnId: UUID,
    val states: List<ColumnStateResponse>,
    val name: String,
    val category: String,
    val displayOrder: Int,
    val cards: List<BoardCardResponse>,
    val wipLimit: Int?,
    val wipExceeded: Boolean,
) {
    companion object {
        /**
         * [PlacedColumn] 을 [BoardColumnWithCardsResponse] 로 변환한다.
         *
         * @param catalog [ColumnStateResponse.catalog] 로 만든 상태 색인. 기본값을 두지 않는 이유는
         *   [BoardColumnResponse.Companion.from] 과 같다.
         */
        fun from(
            placed: PlacedColumn,
            catalog: Map<String, WorkflowStateView>,
        ): BoardColumnWithCardsResponse =
            BoardColumnWithCardsResponse(
                columnId = placed.column.id,
                states = ColumnStateResponse.resolve(placed.column.stateKeys, catalog),
                name = placed.column.name,
                category = placed.column.category,
                displayOrder = placed.column.displayOrder,
                cards = placed.cards.map(BoardCardResponse::from),
                wipLimit = placed.column.wipLimit,
                wipExceeded = placed.column.wipLimit?.let { placed.cards.size > it } ?: false,
            )
    }
}

/**
 * 보드 단건 조회 응답 DTO(컬럼 + 카드 + 신호 필드).
 *
 * @property boardId 보드 UUID.
 * @property projectKey 소속 프로젝트 키.
 * @property name 보드 표시 이름.
 * @property columns 카드가 배치된 컬럼 목록.
 * @property truncated BOARD_CARD_FETCH_LIMIT 초과로 이슈 일부가 누락됐으면 true.
 *   클라이언트가 "보드에 표시되지 않은 이슈가 있습니다" UI 경고를 표시하는 데 사용한다.
 * @property unplacedCount 어느 컬럼에도 매핑되지 않아 보드에서 제외된 이슈 수(E2 미매핑 상태 이슈).
 *   0 이면 미매핑 이슈 없음. 양수이면 워크플로우 상태와 보드 컬럼 간 미싱 매핑이 있음을 의미한다.
 * @property unmappedStates 어느 컬럼에도 매핑되지 않은 워크플로우 상태 목록(R8 · J2). 지라의
 *   **Unmapped statuses** 패널에 대응한다 — 컬럼으로 끌어다 놓을 후보이자, [unplacedCount] 가
 *   양수인 **이유**다. 「몇 건이 빠졌나」만 알던 클라이언트가 「어느 상태가 빠졌나」를 알게 된다.
 * @property swimlaneField 스윔레인 기준 필드 이름. [SwimlaneField.name] 문자열. 예: `"NONE"`, `"ASSIGNEE"`, `"PRIORITY"`.
 *   클라이언트가 스윔레인 UI 활성 여부 및 그룹화 기준을 판단하는 데 사용한다.
 * @property quickFilters 보드에 저장된 퀵필터 목록(created_at ASC, FR-UX-01 Task 7). 보드를 보는 모든
 *   사용자가 공유하는 사전 정의 필터 칩이다. 퀵필터가 없으면 빈 목록.
 * @property canDelete 이 보드를 삭제할 수 있는지 여부(FR-BD-01-2d). 클라이언트는 false 이면 삭제 항목을
 *   **렌더하지 않는다**(비활성이 아니다). 실제 판정은 프로젝트 스코프의
 *   [com.bts.shared.permission.IssuePermission.SOFT_DELETE] 라 같은 프로젝트의 보드끼리 값이 같다 —
 *   per-board 관리자 모델이 들어오기 전까지의 의미론적 근사다. 목록 응답에는 싣지 않는다.
 * @property boardType 보드 종류(`"SCRUM"`/`"KANBAN"`). 클라이언트가 화면 의미를 가르는 축이다(FR-BD-04).
 * @property activeSprint 스크럼 보드의 활성 스프린트. **칸반은 항상 `null`** 이고, 스크럼이라도 시작된
 *   스프린트가 없으면 `null` 이다. `null` 이면 「스프린트를 시작하세요」 빈 상태를 그린다.
 */
data class BoardDetailResponse(
    val boardId: UUID,
    val projectKey: String,
    val name: String,
    val columns: List<BoardColumnWithCardsResponse>,
    val truncated: Boolean,
    val unplacedCount: Int,
    val unmappedStates: List<ColumnStateResponse>,
    val swimlaneField: String,
    val quickFilters: List<QuickFilterResponse>,
    val canDelete: Boolean,
    val boardType: String,
    val activeSprint: ActiveSprintResponse?,
) {
    companion object {
        /**
         * 보드 메타([Board])와 카드 배치 결과([BoardPlacementResult])를 합쳐 응답을 만든다.
         *
         * @param board 보드 메타(boardId/projectKey/name/swimlaneField 출처).
         * @param result 카드 배치 + 신호 필드(truncated/unplacedCount) 결과.
         * @param quickFilters 보드에 저장된 퀵필터 도메인 목록(created_at ASC). 기본값은 빈 목록.
         * @param canDelete 삭제 권한 보유 여부. 기본값 false 는 fail-closed — 권한을 넘기지 않은
         *   호출부가 삭제 UI 를 열어버리는 쪽으로 기울지 않게 한다.
         */
        fun of(
            board: Board,
            result: BoardPlacementResult,
            quickFilters: List<QuickFilter> = emptyList(),
            canDelete: Boolean = false,
        ): BoardDetailResponse {
            // 색인은 한 번만 만든다 — 컬럼마다 다시 만들면 컬럼 수 × 상태 수가 된다(N3).
            val catalog = ColumnStateResponse.catalog(result.stateCatalog)
            return BoardDetailResponse(
                boardId = board.id,
                projectKey = board.projectKey,
                name = board.name,
                columns = result.columns.map { BoardColumnWithCardsResponse.from(it, catalog) },
                truncated = result.truncated,
                unplacedCount = result.unplacedCount,
                unmappedStates = result.unmappedStates.map(ColumnStateResponse::from),
                swimlaneField = board.swimlaneField.name,
                quickFilters = quickFilters.map(QuickFilterResponse::from),
                canDelete = canDelete,
                boardType = board.boardType.name,
                activeSprint = result.activeSprint?.let(ActiveSprintResponse::from),
            )
        }
    }
}

/**
 * 스크럼 보드의 활성 스프린트 요약 (FR-BD-04).
 *
 * 보드 헤더가 「어느 스프린트를 보고 있는지」와 남은 기간을 보여주는 데 필요한 최소 필드만 싣는다.
 * 스프린트 상세(목표·이슈 목록)는 스프린트 API 소관이라 여기서 중복하지 않는다.
 *
 * @property sprintId 활성 스프린트 UUID.
 * @property name 스프린트 표시 이름. 예: `"Sprint 3"`.
 * @property startDate 시작일. 미설정이면 null.
 * @property endDate 종료일. 미설정이면 null.
 */
data class ActiveSprintResponse(
    val sprintId: UUID,
    val name: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
) {
    companion object {
        /** 도메인 [Sprint] 를 보드 헤더용 요약으로 변환한다. */
        fun from(sprint: Sprint): ActiveSprintResponse =
            ActiveSprintResponse(
                sprintId = sprint.id,
                name = sprint.name,
                startDate = sprint.startDate,
                endDate = sprint.endDate,
            )
    }
}

/**
 * 컬럼 WIP 제한 변경 요청 바디.
 *
 * [wipLimit] 가 null 이면 WIP 제한을 해제한다.
 * null 이 아닌 경우 반드시 양수(1 이상)여야 한다. 0 또는 음수는 컨트롤러에서 검증해 400 으로 거부한다
 * (jakarta `@Positive` 가 nullable `Int?` 에서 0 을 통과시키는 한계가 있어 BoardController 가 수동 검증한다).
 *
 * @property wipLimit 새로운 WIP 제한. null 이면 해제. 양수만 허용.
 */
data class UpdateColumnWipLimitRequest(
    val wipLimit: Int?,
)

/**
 * 보드 부분 갱신 요청 바디 — partial update (3-state).
 *
 * [JsonNullable] presence 로 3-state 를 구분한다(`UpdateSprintRequest` 와 같은 관용구).
 * - 필드 부재(undefined, 미전송) = 무변경
 * - 명시 null = 400. 보드는 이름도 스윔레인 기준도 「해제」할 수 없으므로 클리어 의미가 없다
 * - 값 전송 = 해당 값으로 설정
 *
 * 두 필드가 모두 부재이면 400 이다 — 빈 바디 `{}` 가 조용히 200 을 받지 않게 하는 최소 1필드 규칙이다.
 * 이 규칙이 [NotBlank] 시절의 400 을 승계한다.
 *
 * 타입 인자를 `String?` 로 둔 것은 의도적이다. `JsonNullable<String>` 로 두면 `get()` 이 플랫폼 타입이라
 * 명시 null 이 컴파일러 검사 없이 흘러 들어가 500 이 된다 — null 이 도착할 수 있다는 사실을 타입에 남긴다.
 *
 * @property name 새 보드 이름. 미전송=무변경. 전송 시 공백 불가 — 판정은 도메인
 *   [com.bts.agileplanning.domain.Board] 의 init require 가 하고 400 으로 변환된다.
 * @property swimlaneField 스윔레인 기준 필드 이름. 예: `"NONE"`, `"ASSIGNEE"`, `"PRIORITY"`.
 *   미전송=무변경. 알 수 없는 값(예: "EPIC", "foo")은 서비스의 enum 파싱 실패로 400 이 된다.
 */
data class UpdateBoardRequest(
    val name: JsonNullable<String?> = JsonNullable.undefined(),
    val swimlaneField: JsonNullable<String?> = JsonNullable.undefined(),
)

/**
 * 컬럼 생성 요청 DTO (R9 · J2).
 *
 * `stateKeys` 는 **비어 있어도 된다** — 지라는 컬럼을 먼저 만들고 Unmapped 패널에서 상태를
 * 끌어다 놓는다. 그 중간 상태를 표현할 수 없으면 조작 자체가 성립하지 않는다(E1).
 *
 * @property name 컬럼 표시 이름. 공백 불가.
 * @property stateKeys 처음부터 담을 상태 키 목록. 순서가 곧 컬럼 안 드롭존 순서다. 미전송이면 빈 목록.
 * @property displayOrder 표시 순서. 미전송이면 맨 뒤에 붙는다.
 */
data class CreateColumnRequest(
    @field:NotBlank
    val name: String?,
    val stateKeys: List<String> = emptyList(),
    val displayOrder: Int? = null,
)

/**
 * 컬럼 상태 집합 교체 요청 DTO (R9).
 *
 * 집합 **전체**를 받는다 — 추가·제거를 각각의 엔드포인트로 두면 「지금 이 컬럼의 상태 집합」이
 * 클라이언트와 서버 사이에서 갈리고, X1 위반을 한 요청 안에서 판정할 수 없다.
 *
 * @property stateKeys 새 상태 키 목록. 빈 목록이면 그 컬럼의 상태를 전부 뗀다(E1).
 */
data class ReplaceColumnStatesRequest(
    val stateKeys: List<String> = emptyList(),
)

/**
 * 컬럼 WIP 제한 변경 응답 DTO.
 *
 * @property columnId 컬럼 UUID.
 * @property states 이 컬럼에 매핑된 워크플로우 상태 목록(0개 이상 · `display_order` 순). R11.
 * @property name 컬럼 표시 이름.
 * @property category 칸반 카테고리. 담은 상태들의 최댓값(R5)이라 개별 상태와 다를 수 있다.
 * @property displayOrder 컬럼 표시 순서.
 * @property wipLimit 갱신된 WIP 제한. null 이면 무제한.
 */
data class ColumnMetaResponse(
    val columnId: UUID,
    val states: List<ColumnStateResponse>,
    val name: String,
    val category: String,
    val displayOrder: Int,
    val wipLimit: Int?,
) {
    companion object {
        /**
         * 도메인 [BoardColumn] 을 [ColumnMetaResponse] 로 변환한다.
         *
         * @param catalog [ColumnStateResponse.catalog] 로 만든 상태 색인. 기본값을 두지 않는 이유는
         *   [BoardColumnResponse.Companion.from] 과 같다.
         */
        fun from(
            column: BoardColumn,
            catalog: Map<String, WorkflowStateView>,
        ): ColumnMetaResponse =
            ColumnMetaResponse(
                columnId = column.id,
                states = ColumnStateResponse.resolve(column.stateKeys, catalog),
                name = column.name,
                category = column.category,
                displayOrder = column.displayOrder,
                wipLimit = column.wipLimit,
            )
    }
}

/**
 * 보드 스윔레인 변경 응답 DTO.
 *
 * @property boardId 보드 UUID.
 * @property projectKey 소속 프로젝트 키.
 * @property name 보드 표시 이름.
 * @property swimlaneField 갱신된 스윔레인 기준 필드 이름.
 * @property boardType 보드 종류. `"SCRUM"` · `"KANBAN"` (FR-BD-04).
 *   생성 후 변경 경로가 없어(ADR 편차 X3) 이 응답에서 값이 바뀌는 일은 없지만, **종류를 노출하는
 *   DTO(BoardResponse · BoardSummaryResponse · BoardDetailResponse · BoardMetaResponse) 중
 *   여기만 빠져 있으면** 소비자가 「PATCH 응답으로는 종류를 알 수 없다」는 예외를 학습하게 되고
 *   그 예외가 다음 결함이 된다. 형제 DTO 와 같은 계약으로 맞춘다.
 */
data class BoardMetaResponse(
    val boardId: UUID,
    val projectKey: String,
    val name: String,
    val swimlaneField: String,
    val boardType: String,
) {
    companion object {
        /** 도메인 [Board] 를 [BoardMetaResponse] 로 변환한다. */
        fun from(board: Board): BoardMetaResponse =
            BoardMetaResponse(
                boardId = board.id,
                projectKey = board.projectKey,
                name = board.name,
                swimlaneField = board.swimlaneField.name,
                boardType = board.boardType.name,
            )
    }
}

/**
 * 카드 이동(전환) 응답 DTO.
 *
 * @property issueKey 이동한 이슈 키.
 * @property currentStateKey 전환 후 현재 상태 키.
 * @property version 전환 후 갱신된 OCC 버전.
 * @property columnId 이동한 대상 컬럼 UUID(요청 toColumnId echo).
 */
data class MoveCardResponse(
    val issueKey: String,
    val currentStateKey: String,
    val version: Long,
    val columnId: UUID,
) {
    companion object {
        /**
         * 이동 결과([BoardCardMoveResult])를 응답으로 옮긴다.
         *
         * `columnId` 는 **서버가 해석한** 컬럼이다 — 요청이 상태만 지목했어도(R6) 그 상태를 담은
         * 컬럼을 되돌려 주므로, 클라이언트가 컬럼을 다시 계산할 필요가 없다.
         *
         * @param result 이동 결과(전환 + 해석된 컬럼).
         */
        fun of(result: BoardCardMoveResult): MoveCardResponse =
            MoveCardResponse(
                issueKey = result.transition.issueKey,
                currentStateKey = result.transition.currentStateKey,
                version = result.transition.version,
                columnId = result.columnId,
            )
    }
}

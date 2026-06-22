// 보드 REST API 요청/응답 DTO + DataResponse 봉투 — agile-planning BC (FR-BD-01, FR-BD-03)

package com.bts.agileplanning.web.dto

import com.bts.agileplanning.application.BoardPlacementResult
import com.bts.agileplanning.domain.Board
import com.bts.agileplanning.domain.BoardColumn
import com.bts.agileplanning.domain.PlacedColumn
import com.bts.shared.board.BoardIssueView
import com.bts.shared.board.BoardTransitionResult
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
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
 */
data class CreateBoardRequest(
    @field:NotBlank
    val projectKey: String,
    @field:NotBlank
    val name: String,
)

/**
 * 카드 이동(전이) 요청 바디.
 *
 * actor 는 body 로 받지 않는다 — SecurityContext 에서 추출한다(actor 위조 차단).
 *
 * @property toColumnId 이동 대상 컬럼 UUID. 필수.
 * @property expectedVersion 낙관적 락(OCC) 기대 버전. 0 이상 필수.
 * @property resolutionId DONE 카테고리 전이 시 필요한 해결 방안 ID. 불필요하면 null.
 */
data class MoveCardRequest(
    @field:NotNull
    val toColumnId: UUID?,
    @field:NotNull
    @field:PositiveOrZero
    val expectedVersion: Long?,
    val resolutionId: UUID? = null,
)

/**
 * 보드 컬럼 응답 DTO(카드 미포함, 생성 응답용).
 *
 * @property columnId 컬럼 UUID.
 * @property stateKey 매핑된 워크플로우 상태 키.
 * @property name 컬럼 표시 이름.
 * @property category 칸반 카테고리. `"TODO"` · `"IN_PROGRESS"` · `"DONE"`.
 * @property displayOrder 컬럼 표시 순서(오름차순).
 */
data class BoardColumnResponse(
    val columnId: UUID,
    val stateKey: String,
    val name: String,
    val category: String,
    val displayOrder: Int,
) {
    companion object {
        /** 도메인 [BoardColumn] 을 [BoardColumnResponse] 로 변환한다. */
        fun from(column: BoardColumn): BoardColumnResponse =
            BoardColumnResponse(
                columnId = column.id,
                stateKey = column.stateKey,
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
 */
data class BoardResponse(
    val boardId: UUID,
    val projectKey: String,
    val name: String,
    val columns: List<BoardColumnResponse>,
) {
    companion object {
        /** 도메인 [Board] 를 [BoardResponse](컬럼 포함, 카드 미포함) 로 변환한다. */
        fun from(board: Board): BoardResponse =
            BoardResponse(
                boardId = board.id,
                projectKey = board.projectKey,
                name = board.name,
                columns = board.columns.map(BoardColumnResponse::from),
            )
    }
}

/**
 * 보드 목록 항목 응답 DTO(컬럼 미포함).
 *
 * @property boardId 보드 UUID.
 * @property projectKey 소속 프로젝트 키.
 * @property name 보드 표시 이름.
 */
data class BoardSummaryResponse(
    val boardId: UUID,
    val projectKey: String,
    val name: String,
) {
    companion object {
        /** 도메인 [Board] 를 [BoardSummaryResponse](메타만) 로 변환한다. */
        fun from(board: Board): BoardSummaryResponse =
            BoardSummaryResponse(
                boardId = board.id,
                projectKey = board.projectKey,
                name = board.name,
            )
    }
}

/**
 * 보드 카드(이슈) 응답 DTO.
 *
 * NIT(sec NIT-1): `priority` 는 컬럼 내 정렬 내부용이므로 응답에서 제외한다(spec 응답 필드 정합).
 *
 * @property issueKey 이슈 키. 예: `"BTS-1"`.
 * @property summary 이슈 제목.
 * @property assigneeId 담당자 UUID. 미배정이면 null.
 * @property version 낙관적 락(OCC) 버전. 카드 이동 시 expectedVersion 으로 사용.
 */
data class BoardCardResponse(
    val issueKey: String,
    val summary: String,
    val assigneeId: UUID?,
    val version: Long,
) {
    companion object {
        /** cross-BC [BoardIssueView] 를 [BoardCardResponse] 로 변환한다(priority 제외). */
        fun from(card: BoardIssueView): BoardCardResponse =
            BoardCardResponse(
                issueKey = card.key,
                summary = card.summary,
                assigneeId = card.assigneeId,
                version = card.version,
            )
    }
}

/**
 * 보드 단건 조회 컬럼 응답 DTO(카드 포함).
 *
 * @property columnId 컬럼 UUID.
 * @property stateKey 매핑된 워크플로우 상태 키.
 * @property name 컬럼 표시 이름.
 * @property category 칸반 카테고리.
 * @property displayOrder 컬럼 표시 순서.
 * @property cards 이 컬럼에 배치된 카드 목록(priority ASC 정렬).
 * @property wipLimit WIP(Work In Progress) 제한 수. null 이면 무제한.
 *   [com.bts.agileplanning.domain.BoardColumn.wipLimit] 값을 그대로 반영한다.
 * @property wipExceeded 현재 카드 수가 [wipLimit] 를 초과하면 true. [wipLimit] 가 null 이거나
 *   카드 수가 [wipLimit] 이하이면 false. 같을 때도 초과로 판단하지 않는다(strictly greater than).
 */
data class BoardColumnWithCardsResponse(
    val columnId: UUID,
    val stateKey: String,
    val name: String,
    val category: String,
    val displayOrder: Int,
    val cards: List<BoardCardResponse>,
    val wipLimit: Int?,
    val wipExceeded: Boolean,
) {
    companion object {
        /** [PlacedColumn] 을 [BoardColumnWithCardsResponse] 로 변환한다. */
        fun from(placed: PlacedColumn): BoardColumnWithCardsResponse =
            BoardColumnWithCardsResponse(
                columnId = placed.column.id,
                stateKey = placed.column.stateKey,
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
 * @property swimlaneField 스윔레인 기준 필드 이름. [SwimlaneField.name] 문자열. 예: `"NONE"`, `"ASSIGNEE"`, `"PRIORITY"`.
 *   클라이언트가 스윔레인 UI 활성 여부 및 그룹화 기준을 판단하는 데 사용한다.
 */
data class BoardDetailResponse(
    val boardId: UUID,
    val projectKey: String,
    val name: String,
    val columns: List<BoardColumnWithCardsResponse>,
    val truncated: Boolean,
    val unplacedCount: Int,
    val swimlaneField: String,
) {
    companion object {
        /**
         * 보드 메타([Board])와 카드 배치 결과([BoardPlacementResult])를 합쳐 응답을 만든다.
         *
         * @param board 보드 메타(boardId/projectKey/name/swimlaneField 출처).
         * @param result 카드 배치 + 신호 필드(truncated/unplacedCount) 결과.
         */
        fun of(
            board: Board,
            result: BoardPlacementResult,
        ): BoardDetailResponse =
            BoardDetailResponse(
                boardId = board.id,
                projectKey = board.projectKey,
                name = board.name,
                columns = result.columns.map(BoardColumnWithCardsResponse::from),
                truncated = result.truncated,
                unplacedCount = result.unplacedCount,
                swimlaneField = board.swimlaneField.name,
            )
    }
}

/**
 * 컬럼 WIP 제한 변경 요청 바디.
 *
 * [wipLimit] 가 null 이면 WIP 제한을 해제한다.
 * null 이 아닌 경우 반드시 양수(1 이상)여야 한다 — [Positive] 검증.
 * 0 또는 음수는 400 으로 거부된다.
 *
 * @property wipLimit 새로운 WIP 제한. null 이면 해제. 양수만 허용.
 */
data class UpdateColumnWipLimitRequest(
    @field:Min(value = 1, message = "wipLimit 는 1 이상이어야 합니다.")
    val wipLimit: Int?,
)

/**
 * 보드 스윔레인 기준 변경 요청 바디.
 *
 * [swimlaneField] 는 [com.bts.agileplanning.domain.SwimlaneField] enum 이름 문자열이어야 한다.
 * 빈 문자열은 400 으로 거부된다 — [NotBlank] 검증.
 * 알 수 없는 값(예: "EPIC", "foo")은 서비스 계층에서 enum 파싱 실패 시 400 으로 거부된다.
 *
 * @property swimlaneField 스윔레인 기준 필드 이름. 예: `"NONE"`, `"ASSIGNEE"`, `"PRIORITY"`.
 */
data class UpdateBoardSwimlaneRequest(
    @field:NotBlank
    val swimlaneField: String?,
)

/**
 * 컬럼 WIP 제한 변경 응답 DTO.
 *
 * @property columnId 컬럼 UUID.
 * @property stateKey 매핑된 워크플로우 상태 키.
 * @property name 컬럼 표시 이름.
 * @property category 칸반 카테고리.
 * @property displayOrder 컬럼 표시 순서.
 * @property wipLimit 갱신된 WIP 제한. null 이면 무제한.
 */
data class ColumnMetaResponse(
    val columnId: UUID,
    val stateKey: String,
    val name: String,
    val category: String,
    val displayOrder: Int,
    val wipLimit: Int?,
) {
    companion object {
        /** 도메인 [BoardColumn] 을 [ColumnMetaResponse] 로 변환한다. */
        fun from(column: BoardColumn): ColumnMetaResponse =
            ColumnMetaResponse(
                columnId = column.id,
                stateKey = column.stateKey,
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
 */
data class BoardMetaResponse(
    val boardId: UUID,
    val projectKey: String,
    val name: String,
    val swimlaneField: String,
) {
    companion object {
        /** 도메인 [Board] 를 [BoardMetaResponse] 로 변환한다. */
        fun from(board: Board): BoardMetaResponse =
            BoardMetaResponse(
                boardId = board.id,
                projectKey = board.projectKey,
                name = board.name,
                swimlaneField = board.swimlaneField.name,
            )
    }
}

/**
 * 카드 이동(전이) 응답 DTO.
 *
 * @property issueKey 이동한 이슈 키.
 * @property currentStateKey 전이 후 현재 상태 키.
 * @property version 전이 후 갱신된 OCC 버전.
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
         * 전이 결과([BoardTransitionResult])와 대상 컬럼 UUID 를 합쳐 응답을 만든다.
         *
         * @param result 전이 결과(issueKey/currentStateKey/version 출처).
         * @param toColumnId 이동 대상 컬럼 UUID(응답 columnId echo).
         */
        fun of(
            result: BoardTransitionResult,
            toColumnId: UUID,
        ): MoveCardResponse =
            MoveCardResponse(
                issueKey = result.issueKey,
                currentStateKey = result.currentStateKey,
                version = result.version,
                columnId = toColumnId,
            )
    }
}

// 카드 레이아웃 탭 REST 컨트롤러 — 뷰별 PATCH (부채 177 Task 8 · J17·J18)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.CardLayoutSettingsService
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.shared.permission.IssuePermissionResolver
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 카드 레이아웃 탭 REST 컨트롤러 (부채 177 Task 8).
 *
 * ★ RED 단계의 껍데기다 — 엔드포인트 시그니처만 있고 게이트·검증·저장은 GREEN 에서 넣는다.
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/card-layout")
class BoardCardLayoutController(
    private val service: CardLayoutSettingsService,
    private val boardRepository: BoardRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 요청에 담긴 뷰의 카드 레이아웃을 교체한다.
     *
     * @param boardId path variable 대상 보드 UUID.
     * @param request 뷰별 필드 키 목록.
     * @return 200 OK + 저장 후 전체 구성.
     */
    @PatchMapping
    fun replaceCardLayout(
        @PathVariable boardId: UUID,
        @Valid @RequestBody request: CardLayoutRequest,
    ): ResponseEntity<DataResponse<CardLayoutResponse>> {
        log.info("BoardCardLayoutController.replaceCardLayout boardId={} views={}", boardId, request.cardLayout.keys)
        return ResponseEntity.ok(DataResponse(CardLayoutResponse(emptyMap())))
    }
}

/**
 * 카드 레이아웃 PATCH 요청 바디.
 *
 * @property cardLayout `viewScope → 필드 키 목록`. 순서가 곧 카드에서의 자리다.
 */
data class CardLayoutRequest(
    @field:NotEmpty(message = "cardLayout 은 최소 한 뷰를 담아야 합니다.")
    val cardLayout: Map<String, List<String>>,
)

/**
 * 카드 레이아웃 응답 바디.
 *
 * @property cardLayout 저장 후 `viewScope → 필드 키 목록`.
 */
data class CardLayoutResponse(
    val cardLayout: Map<String, List<String>>,
)

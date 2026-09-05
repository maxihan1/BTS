// 보드 설정 「추정」 탭 REST 컨트롤러 — 시간 추적 PATCH (부채 177 · J36·J37)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.EstimationSettingsService
import com.bts.agileplanning.web.dto.DataResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 추정 탭 요청 바디.
 *
 * @property timeTracking `"NONE"` 또는 `"REMAINING_AND_SPENT"`(J36). 허용값 판정은 서비스가 진다 —
 *   DTO 는 1차 방어일 뿐이다(memory: patch-merge-domain-bypass).
 */
data class EstimationSettingsRequest(
    @field:NotBlank
    val timeTracking: String,
)

/**
 * 추정 탭 응답 바디.
 *
 * @property timeTracking 저장된 시간 추적 값.
 */
data class EstimationSettingsResponse(
    val timeTracking: String,
)

/**
 * 보드 설정 「추정」 탭 REST 컨트롤러 — **RED 단계 껍데기**.
 *
 * 아직 권한 게이트도 보드 종류 판정도 없다. GREEN 단계에서 채운다.
 * [BoardController] 를 키우지 않으려고 탭별 컨트롤러로 나눈다(부채 157 · 스펙 C-1) —
 * [BoardQuickFilterController] 가 같은 이유로 먼저 난 선례다.
 *
 * @param service 시간 추적 저장 유스케이스.
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/estimation")
class BoardEstimationController(
    private val service: EstimationSettingsService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 시간 추적 설정을 갱신한다.
     *
     * @param boardId path variable 보드 UUID.
     * @param request 요청 바디.
     * @return 200 OK + [EstimationSettingsResponse].
     */
    @PatchMapping
    fun updateEstimation(
        @PathVariable boardId: UUID,
        @Valid @RequestBody request: EstimationSettingsRequest,
    ): ResponseEntity<DataResponse<EstimationSettingsResponse>> {
        log.info("BoardEstimationController.updateEstimation boardId={}", boardId)

        val saved = service.updateTimeTracking(boardId, request.timeTracking)
        return ResponseEntity.ok(DataResponse(EstimationSettingsResponse(saved)))
    }
}

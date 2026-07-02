// 대시보드 공유 토큰 관리 REST API 컨트롤러 — 발급·목록·취소 (FR-DB-03, 인증·소유자 전용)

package com.bts.notification.dashboard.web

import com.bts.notification.dashboard.application.DashboardService
import com.bts.notification.dashboard.web.dto.IssueShareTokenRequest
import com.bts.notification.dashboard.web.dto.IssuedShareTokenResponse
import com.bts.notification.dashboard.web.dto.ShareTokenListResponse
import com.bts.notification.dashboard.web.dto.ShareTokenSummaryResponse
import com.bts.notification.web.DataResponse
import com.bts.notification.web.currentActorId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 대시보드 공유 토큰 관리 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - POST   /api/v1/dashboards/{id}/shares            — 공유 토큰 발급 (201, 원문 1회 노출)
 * - GET    /api/v1/dashboards/{id}/shares            — 발급 목록 조회 (200, 원문·해시 미노출)
 * - DELETE /api/v1/dashboards/{id}/shares/{shareId}  — 공유 토큰 취소 (204)
 *
 * 모두 인증 + 대시보드 소유자 전용이다(익명 공개 조회는 별도 PublicDashboardController).
 * currentActorId() 로 actor 를 추출하며, 이는 항상 리소스 조회보다 먼저 수행한다
 * (memory: auth-extraction-before-resource-lookup 교훈).
 *
 * 로그에는 id·actorId 만 남기고 토큰 원문·해시는 절대 출력하지 않는다.
 *
 * @param service 대시보드 공유 토큰 유스케이스를 포함한 애플리케이션 서비스
 */
@RestController
@RequestMapping("/api/v1/dashboards/{id}/shares")
class DashboardShareController(
    private val service: DashboardService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 대시보드 공유 토큰을 발급한다.
     *
     * 요청 바디는 선택이다(전체 생략 또는 expiresAt 생략 모두 허용 — 무기한 발급).
     *
     * @param id 대상 대시보드 식별자
     * @param request 발급 요청 바디 (선택)
     * @return 201 Created + 원문 토큰을 포함한 응답
     */
    @PostMapping
    fun issue(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: IssueShareTokenRequest?,
    ): ResponseEntity<DataResponse<IssuedShareTokenResponse>> {
        val actorId = currentActorId()

        log.info("DashboardShareController.issue actorId={}, dashboardId={}", actorId, id)

        val issued = service.issueShareToken(actorId = actorId, dashboardId = id, expiresAt = request?.expiresAt)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(DataResponse(data = IssuedShareTokenResponse.from(issued)))
    }

    /**
     * 대시보드에 발급된 공유 토큰 목록을 조회한다.
     *
     * 응답에는 원문·해시가 포함되지 않는다(요약 DTO 에 필드 자체가 없음).
     *
     * @param id 대상 대시보드 식별자
     * @return 200 OK + 공유 토큰 요약 목록
     */
    @GetMapping
    fun list(
        @PathVariable id: UUID,
    ): ResponseEntity<DataResponse<ShareTokenListResponse>> {
        val actorId = currentActorId()

        log.debug("DashboardShareController.list actorId={}, dashboardId={}", actorId, id)

        val items = service.listShareTokens(actorId, id).map { ShareTokenSummaryResponse.from(it) }

        return ResponseEntity.ok(DataResponse(data = ShareTokenListResponse(items)))
    }

    /**
     * 대시보드 공유 토큰을 취소한다.
     *
     * @param id 대상 대시보드 식별자
     * @param shareId 취소할 공유 토큰 식별자
     */
    @DeleteMapping("/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @PathVariable id: UUID,
        @PathVariable shareId: UUID,
    ) {
        val actorId = currentActorId()
        log.info("DashboardShareController.revoke actorId={}, dashboardId={}, shareId={}", actorId, id, shareId)
        service.revokeShareToken(actorId, id, shareId)
    }
}

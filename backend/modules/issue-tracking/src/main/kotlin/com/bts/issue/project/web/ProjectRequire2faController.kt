// PATCH /api/v1/projects/{projectIdOrKey}/require-2fa SYSTEM_ADMIN 전용 컨트롤러 (FR-MF-04 Task 3)

package com.bts.issue.project.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.project.application.ProjectRequire2faApplicationService
import com.bts.issue.project.application.Require2faResult
import com.bts.issue.project.web.dto.ChangeRequire2faRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 프로젝트 require_2fa(민감 프로젝트 여부) REST API 컨트롤러.
 *
 * 엔드포인트 — PATCH /api/v1/projects/{projectIdOrKey}/require-2fa.
 * - SYSTEM_ADMIN 전용. 비관리자 → 403, 미인증 → 401.
 *
 * ## 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 모든 트랜잭션은 [ProjectRequire2faApplicationService] 의 @Transactional 이 담당한다.
 *
 * ## 권한 가드
 * [CurrentActor.current] 로 actorId 추출 → [ProjectRequire2faApplicationService.toggle] 내부에서
 * [com.bts.shared.permission.SystemPermissionResolver.isSystemAdmin] 으로 검증한다.
 *
 * @param service require_2fa 토글 Application Service.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectIdOrKey}")
class ProjectRequire2faController(
    private val service: ProjectRequire2faApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 require_2fa(민감 프로젝트 여부)를 토글한다.
     *
     * SYSTEM_ADMIN 전용. 비관리자 → [com.bts.issue.project.domain.Require2faForbiddenException] → 403.
     *
     * @param projectIdOrKey path variable — 프로젝트 UUID 또는 projectKey.
     * @param request 요청 바디 — [ChangeRequire2faRequest.requireTwoFactor].
     * @return 200 OK + [Require2faResponse] body.
     * @throws com.bts.issue.project.domain.Require2faProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.project.domain.Require2faForbiddenException SYSTEM_ADMIN 아님 → 403
     */
    @PatchMapping("/require-2fa")
    fun toggleRequire2fa(
        @PathVariable projectIdOrKey: String,
        @RequestBody request: ChangeRequire2faRequest,
    ): ResponseEntity<DataResponse<Require2faResponse>> {
        val actor = CurrentActor.current()
        log.info(
            "ProjectRequire2faController.toggleRequire2fa actor={} projectIdOrKey={} requireTwoFactor={}",
            actor.value,
            projectIdOrKey,
            request.requireTwoFactor,
        )
        val result =
            service.toggle(
                actorId = actor.value,
                projectIdOrKey = projectIdOrKey,
                requireTwoFactor = request.requireTwoFactor,
            )
        return ResponseEntity.ok(DataResponse(data = Require2faResponse.from(result)))
    }
}

/**
 * require_2fa 토글 응답 DTO.
 *
 * @property projectId 프로젝트 UUID.
 * @property projectKey 프로젝트 키.
 * @property requireTwoFactor 갱신된 require_2fa 값.
 */
data class Require2faResponse(
    val projectId: UUID,
    val projectKey: String,
    val requireTwoFactor: Boolean,
) {
    companion object {
        /**
         * [Require2faResult] 를 [Require2faResponse] 로 변환한다.
         *
         * @param result ApplicationService 반환값.
         * @return HTTP 응답 DTO.
         */
        fun from(result: Require2faResult): Require2faResponse =
            Require2faResponse(
                projectId = result.projectId,
                projectKey = result.projectKey,
                requireTwoFactor = result.requireTwoFactor,
            )
    }
}

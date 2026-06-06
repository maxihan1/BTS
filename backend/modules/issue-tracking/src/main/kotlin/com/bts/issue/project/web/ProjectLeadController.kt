// 프로젝트 리드 지정·해제 REST 컨트롤러 — PATCH /lead (FR-CM-04)

package com.bts.issue.project.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.project.application.ProjectLeadApplicationService
import com.bts.issue.project.web.dto.ChangeProjectLeadRequest
import com.bts.issue.project.web.dto.ProjectLeadResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** actorId placeholder — FR-PM-03 실 추출 이연. SecurityConfig 가 401 을 보장한다. */
private val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

/**
 * 프로젝트 리드 REST API 컨트롤러.
 *
 * 엔드포인트 — `/api/v1/projects/{projectIdOrKey}/lead`.
 * - PATCH — 리드 지정 / 해제 (2-state) → 200
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 모든 트랜잭션은 [ProjectLeadApplicationService] 의 `@Transactional` 이 담당한다.
 *
 * ### actorId
 * FR-PM-03 이전까지 [SYSTEM_ACTOR_UUID] 를 placeholder 로 사용한다.
 * [com.bts.issue.component.web.ComponentController] 와 동형 패턴.
 *
 * @param service 프로젝트 리드 Application Service.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectIdOrKey}")
class ProjectLeadController(
    private val service: ProjectLeadApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 리드를 조회한다.
     *
     * READ 는 권한 게이트 없음 — Jira 동일 정책.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @return 200 OK + [ProjectLeadResponse] body.
     * @throws com.bts.issue.project.domain.ProjectLeadProjectNotFoundException 프로젝트 미존재 → 404
     */
    @GetMapping("/lead")
    fun getLead(
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<DataResponse<ProjectLeadResponse>> {
        log.info("ProjectLeadController.getLead projectIdOrKey={}", projectIdOrKey)
        val result = service.getLead(projectIdOrKey)
        return ResponseEntity.ok(DataResponse(data = ProjectLeadResponse.from(result)))
    }

    /**
     * 프로젝트 리드를 지정하거나 해제한다.
     *
     * [ChangeProjectLeadRequest.leadUserId] 가 null 이면 해제, UUID 이면 지정.
     * 2-state: [com.bts.issue.component.web.ComponentController.changeLead] 와 동형 패턴.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param request 리드 변경 요청 바디.
     * @return 200 OK + [ProjectLeadResponse] body.
     * @throws com.bts.issue.project.domain.ProjectLeadProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.project.domain.ProjectLeadNotFoundException 리드 사용자 미존재 → 422
     */
    @PatchMapping("/lead")
    fun changeLead(
        @PathVariable projectIdOrKey: String,
        @RequestBody request: ChangeProjectLeadRequest,
    ): ResponseEntity<DataResponse<ProjectLeadResponse>> {
        log.info(
            "ProjectLeadController.changeLead projectIdOrKey={} leadUserId={}",
            projectIdOrKey,
            request.leadUserId,
        )
        val result =
            service.changeLead(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                leadUserId = request.leadUserId,
            )
        return ResponseEntity.ok(DataResponse(data = ProjectLeadResponse.from(result)))
    }
}

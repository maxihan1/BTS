// 스프린트 REST API 컨트롤러 — CRUD + 상태 전이 + 이슈 할당/해제 (FR-BL-02 Task 5)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.SprintApplicationService
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.web.dto.AssignIssueRequest
import com.bts.agileplanning.web.dto.CreateSprintRequest
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.agileplanning.web.dto.SprintResponse
import com.bts.agileplanning.web.dto.UpdateSprintRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.util.UUID

/**
 * 스프린트 REST API 컨트롤러 — agile-planning BC.
 *
 * 엔드포인트 목록.
 * - POST   `/api/v1/sprints`                            — 스프린트 생성. 201.
 * - GET    `/api/v1/sprints/{id}`                       — 스프린트 단건 조회. 200.
 * - GET    `/api/v1/sprints?projectKey=`                — 프로젝트별 목록 조회. 200.
 * - PATCH  `/api/v1/sprints/{id}`                       — 스프린트 수정. 200.
 * - DELETE `/api/v1/sprints/{id}`                       — 스프린트 소프트 삭제. 204.
 * - POST   `/api/v1/sprints/{id}/start`                 — 스프린트 시작 (PLANNED -> ACTIVE). 200.
 * - POST   `/api/v1/sprints/{id}/complete`              — 스프린트 완료 (ACTIVE -> COMPLETED). 200.
 * - POST   `/api/v1/sprints/{id}/issues`                — 이슈 할당. 201.
 * - DELETE `/api/v1/sprints/{id}/issues/{issueKey}`     — 이슈 제거. 204.
 *
 * ### 책임 분리
 * 권한 판정은 [SprintApplicationService] 가 내부에서 수행한다. 컨트롤러는 actor 추출과 service 위임만 담당한다.
 * 권한 거부 시 service 가 [ResponseStatusException](403) 을 던지며, [SprintExceptionHandler] 가 처리한다.
 *
 * ### 처리 순서 (존재 probe 차단)
 * 1. actor 추출([currentActorId]) — 미인증이면 401(리소스 조회 이전에 차단).
 * 2. service 위임 — service 내부에서 스프린트 조회(404) -> 권한 판정(403) -> 동작 순으로 처리한다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 트랜잭션은 [SprintApplicationService] 가 개시한다.
 *
 * @param service 스프린트 유스케이스 서비스.
 */
@RestController
@RequestMapping("/api/v1/sprints")
class SprintController(
    private val service: SprintApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 스프린트를 생성한다.
     *
     * actor 추출 후 service 에 위임한다. service 내부에서 projectKey 로 권한을 판정한다.
     *
     * @param request 스프린트 생성 요청 바디.
     * @return 201 Created + [SprintResponse] + Location 헤더.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateSprintRequest,
    ): ResponseEntity<DataResponse<SprintResponse>> {
        log.info("SprintController.create projectKey={}", request.projectKey)

        val actor = currentActorId()
        val sprint =
            service.create(
                actorId = actor,
                projectKey = request.projectKey,
                name = request.name,
                goal = request.goal,
                startDate = request.startDate,
                endDate = request.endDate,
            )
        val location = URI.create("/api/v1/sprints/${sprint.id}")
        return ResponseEntity.created(location).body(DataResponse(SprintResponse.from(sprint)))
    }

    /**
     * 스프린트 단건을 조회한다.
     *
     * @param id path variable 스프린트 UUID.
     * @return 200 OK + [SprintResponse].
     * @throws ResponseStatusException 401 — 미인증.
     * @throws com.bts.agileplanning.application.SprintNotFoundException 404 — 스프린트 미존재.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     */
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<DataResponse<SprintResponse>> {
        log.info("SprintController.get id={}", id)

        val actor = currentActorId()
        val sprint = service.get(actorId = actor, sprintId = id)
        return ResponseEntity.ok(DataResponse(SprintResponse.from(sprint)))
    }

    /**
     * 프로젝트별 스프린트 목록을 조회한다.
     *
     * @param projectKey 조회할 프로젝트 키.
     * @param status 상태 필터. null 이면 전체 상태 반환.
     * @return 200 OK + [SprintResponse] 목록.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     */
    @GetMapping
    fun list(
        @RequestParam projectKey: String,
        @RequestParam(required = false) status: SprintStatus?,
    ): ResponseEntity<DataResponse<List<SprintResponse>>> {
        log.info("SprintController.list projectKey={} status={}", projectKey, status)

        val actor = currentActorId()
        val sprints = service.list(actorId = actor, projectKey = projectKey, statusFilter = status)
        return ResponseEntity.ok(DataResponse(sprints.map(SprintResponse::from)))
    }

    /**
     * 스프린트 메타 정보(이름·목표·기간)를 수정한다 — partial update (3-state).
     *
     * 미전송 필드는 기존 값을 유지하고, 명시 null 은 해당 값을 클리어한다.
     * name 이 전송된 경우 공백이면 400 을 반환한다.
     *
     * @param id path variable 스프린트 UUID.
     * @param request 수정 요청 바디. 미전송 필드는 무변경.
     * @return 200 OK + 갱신된 [SprintResponse].
     * @throws ResponseStatusException 401 — 미인증.
     * @throws com.bts.agileplanning.application.SprintNotFoundException 404 — 스프린트 미존재.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     */
    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateSprintRequest,
    ): ResponseEntity<DataResponse<SprintResponse>> {
        log.info("SprintController.update id={}", id)

        val actor = currentActorId()
        val sprint =
            service.update(
                actorId = actor,
                sprintId = id,
                name = request.name,
                goal = request.goal,
                startDate = request.startDate,
                endDate = request.endDate,
                version = request.version,
            )
        return ResponseEntity.ok(DataResponse(SprintResponse.from(sprint)))
    }

    /**
     * 스프린트를 소프트 삭제한다.
     *
     * @param id path variable 스프린트 UUID.
     * @return 204 No Content.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws com.bts.agileplanning.application.SprintNotFoundException 404 — 스프린트 미존재.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     */
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        log.info("SprintController.delete id={}", id)

        val actor = currentActorId()
        service.softDelete(actorId = actor, sprintId = id)
        return ResponseEntity.noContent().build()
    }

    /**
     * 스프린트를 시작한다 (PLANNED -> ACTIVE).
     *
     * @param id path variable 스프린트 UUID.
     * @return 200 OK + ACTIVE 상태의 [SprintResponse].
     * @throws ResponseStatusException 401 — 미인증.
     * @throws com.bts.agileplanning.application.SprintNotFoundException 404 — 스프린트 미존재.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     * @throws com.bts.agileplanning.domain.InvalidSprintTransitionException 409 — 허용되지 않는 전이.
     */
    @PostMapping("/{id}/start")
    fun start(
        @PathVariable id: UUID,
    ): ResponseEntity<DataResponse<SprintResponse>> {
        log.info("SprintController.start id={}", id)

        val actor = currentActorId()
        val sprint = service.start(actorId = actor, sprintId = id)
        return ResponseEntity.ok(DataResponse(SprintResponse.from(sprint)))
    }

    /**
     * 스프린트를 완료 처리한다 (ACTIVE -> COMPLETED).
     *
     * @param id path variable 스프린트 UUID.
     * @return 200 OK + COMPLETED 상태의 [SprintResponse].
     * @throws ResponseStatusException 401 — 미인증.
     * @throws com.bts.agileplanning.application.SprintNotFoundException 404 — 스프린트 미존재.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     * @throws com.bts.agileplanning.domain.InvalidSprintTransitionException 409 — 허용되지 않는 전이.
     */
    @PostMapping("/{id}/complete")
    fun complete(
        @PathVariable id: UUID,
    ): ResponseEntity<DataResponse<SprintResponse>> {
        log.info("SprintController.complete id={}", id)

        val actor = currentActorId()
        val sprint = service.complete(actorId = actor, sprintId = id)
        return ResponseEntity.ok(DataResponse(SprintResponse.from(sprint)))
    }

    /**
     * 이슈를 스프린트에 할당한다.
     *
     * @param id path variable 스프린트 UUID.
     * @param request 이슈 할당 요청 바디.
     * @return 201 Created.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws com.bts.agileplanning.application.SprintNotFoundException 404 — 스프린트 미존재.
     * @throws ResponseStatusException 403 — UPDATE 권한 미충족.
     * @throws ResponseStatusException 404 — 이슈 미가시 (probe 차단).
     * @throws ResponseStatusException 409 — COMPLETED 스프린트 할당 불가.
     */
    @PostMapping("/{id}/issues")
    fun assignIssue(
        @PathVariable id: UUID,
        @Valid @RequestBody request: AssignIssueRequest,
    ): ResponseEntity<Void> {
        log.info("SprintController.assignIssue sprintId={} issueKey={}", id, request.issueKey)

        val actor = currentActorId()
        service.assignIssue(actorId = actor, sprintId = id, issueKey = request.issueKey)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    /**
     * 이슈를 스프린트에서 제거한다.
     *
     * 멱등 연산이다 — 이미 제거된 이슈 키를 전달해도 204 를 반환한다.
     *
     * @param id path variable 스프린트 UUID.
     * @param issueKey path variable 제거할 이슈 키.
     * @return 204 No Content.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws com.bts.agileplanning.application.SprintNotFoundException 404 — 스프린트 미존재.
     * @throws ResponseStatusException 403 — UPDATE 권한 미충족.
     */
    @DeleteMapping("/{id}/issues/{issueKey}")
    fun unassignIssue(
        @PathVariable id: UUID,
        @PathVariable issueKey: String,
    ): ResponseEntity<Void> {
        log.info("SprintController.unassignIssue sprintId={} issueKey={}", id, issueKey)

        val actor = currentActorId()
        service.unassignIssue(actorId = actor, sprintId = id, issueKey = issueKey)
        return ResponseEntity.noContent().build()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출한다.
     *
     * actor 추출은 리소스 조회보다 먼저 수행해야 한다(미인증자의 존재 probe 차단,
     * memory: auth-extraction-before-resource-lookup 교훈). 미인증·익명·비-UUID 주체는 401 로 거부한다.
     *
     * @return 인증 주체 UUID.
     * @throws ResponseStatusException 401 — 인증이 없거나 주체가 유효한 UUID 가 아닐 때.
     */
    private fun currentActorId(): UUID {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        return try {
            UUID.fromString(authentication.name)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", e)
        }
    }
}

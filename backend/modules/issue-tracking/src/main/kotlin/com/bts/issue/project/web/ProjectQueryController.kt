// GET /api/v1/projects, /api/v1/projects/{projectIdOrKey} 프로젝트 목록/단건 조회 컨트롤러 (FR-PJ PR-3 Task 3/4)

package com.bts.issue.project.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.project.query.ProjectQueryService
import com.bts.issue.project.web.dto.ProjectResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인 사용자가 접근 가능한 프로젝트 목록/단건 REST API 컨트롤러 (FR-PJ PR-3).
 *
 * 엔드포인트 — GET /api/v1/projects, GET /api/v1/projects/{projectIdOrKey}.
 *
 * ### 이중 인증 가드
 * 1. 클래스 레벨 `@PreAuthorize("isAuthenticated()")` + prod SecurityConfig 필터 체인(미인증 401).
 * 2. 핸들러의 [CurrentActor.current] — 미인증/nil-UUID/형식오류 시 401.
 *
 * ### actor 추출 순서 — 리소스 조회보다 먼저
 * [CurrentActor.current] 를 **가장 먼저** 추출한다. 목록/단건 조회([ProjectQueryService.listAccessible],
 * [ProjectQueryService.getOne])는 actor 추출 이후에만 호출되므로, 미인증자는 서비스에 도달하지 못한다
 * ([ProjectCreateController] 동형 순서 선례).
 *
 * ### 단건 조회 권한 — BROWSE 게이트 (Task 4)
 * 단건 조회의 BROWSE 권한 판정은 컨트롤러가 아니라 [ProjectQueryService.getOne] 내부에서 수행한다
 * (존재→권한→조회 순서를 서비스가 원자적으로 소유, [SprintBurndownService] 동형 선례).
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 읽기 전용 트랜잭션은
 * [ProjectQueryService] 의 `@Transactional(readOnly = true)` 가 담당한다.
 *
 * @param projectQueryService 프로젝트 목록/단건 조회 Application Service.
 */
@RestController
@RequestMapping("/api/v1/projects")
@PreAuthorize("isAuthenticated()")
class ProjectQueryController(
    private val projectQueryService: ProjectQueryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 로그인 사용자가 접근 가능한 프로젝트 목록을 반환한다.
     *
     * @return 200 OK + `{ "data": [ ... ] }` (name 오름차순, fail-closed — 멤버십 없으면 빈 배열).
     * @throws org.springframework.web.server.ResponseStatusException 미인증 → 401 (CurrentActor).
     */
    @GetMapping
    fun list(): ResponseEntity<DataResponse<List<ProjectResponse>>> {
        // 1. actor 추출 — 리소스 조회보다 먼저 (미인증 401)
        val actor = CurrentActor.current()

        log.debug("ProjectQueryController.list actor={}", actor.value)

        // 2. 접근 가능한 프로젝트 목록 조회
        val projects = projectQueryService.listAccessible(actor.value).map(ProjectResponse::from)
        return ResponseEntity.ok(DataResponse(projects))
    }

    /**
     * 프로젝트 단건을 조회한다 (BROWSE 게이트, Task 4).
     *
     * @param projectIdOrKey 조회 대상 프로젝트 UUID 문자열 또는 projectKey.
     * @return 200 OK + `{ "data": { ... } }`.
     * @throws org.springframework.web.server.ResponseStatusException 미인증 → 401 (CurrentActor).
     * @throws com.bts.issue.project.query.ProjectQueryNotFoundException 프로젝트 미존재 → 404.
     * @throws com.bts.issue.project.query.ProjectBrowseForbiddenException BROWSE 권한 없음 → 403.
     */
    @GetMapping("/{projectIdOrKey}")
    fun getOne(
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<DataResponse<ProjectResponse>> {
        // 1. actor 추출 — 리소스 조회보다 먼저 (미인증 401)
        val actor = CurrentActor.current()

        log.debug("ProjectQueryController.getOne actor={} projectIdOrKey={}", actor.value, projectIdOrKey)

        // 2. 단건 조회 (존재→권한→조회는 서비스가 원자적으로 소유)
        val project = projectQueryService.getOne(actor.value, projectIdOrKey)
        return ResponseEntity.ok(DataResponse(ProjectResponse.from(project)))
    }
}

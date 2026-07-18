// POST /api/v1/projects 프로젝트 생성 컨트롤러 — CurrentActor + CREATE_PROJECT 전역권한 게이트 (FR-PJ-01 Task 7)

package com.bts.issue.project.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.project.application.ProjectCreateApplicationService
import com.bts.issue.project.domain.Project
import com.bts.issue.project.web.dto.CreateProjectRequest
import com.bts.shared.permission.GlobalPermissionCodes
import com.bts.shared.permission.SystemPermissionResolver
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 프로젝트 생성 REST API 컨트롤러 (FR-PJ-01).
 *
 * 엔드포인트 — POST /api/v1/projects.
 *
 * ### 이중 인증 가드
 * 1. 클래스 레벨 `@PreAuthorize("isAuthenticated()")` + prod SecurityConfig 필터 체인(미인증 401).
 * 2. 핸들러의 [CurrentActor.current] — 미인증/nil-UUID/형식오류 시 401.
 *    `@PreAuthorize hasRole` 로 게이트하지 않는 이유 — PAT 경로에 role claim 이 없어 403 이 전멸한다
 *    (GlobalPermissionGrantController KDoc 선례). 권한은 아래 DB 진실원천 조회로 판정한다.
 *
 * ### actor 추출 순서 — 리소스 조회보다 먼저 (PJ1-3)
 * [CurrentActor.current] 로 actor 를 **가장 먼저** 추출한다. key 중복조회(insert)는
 * [ProjectCreateApplicationService.create] 안에서 일어나므로, 미인증자는 서비스에 도달하지 못해
 * key 존재 여부를 probe 할 수 없다([com.bts.issue.comment.web.CommentController] 동형 순서 선례).
 *
 * ### 전역권한 게이트 — hasGlobalPermission (hasGrant 직접호출 금지)
 * [SystemPermissionResolver.hasGlobalPermission] 로 CREATE_PROJECT 를 판정한다. prod 판정식은
 * `grant OR isSystemAdmin` 이므로, `GlobalPermissionGrantRepository.hasGrant` 를 직접 부르면
 * SYSTEM_ADMIN 이 탈락한다(FR-PM-10 뒷항 소실). 반드시 이 포트 창구로만 판정한다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 모든 트랜잭션은
 * [ProjectCreateApplicationService] 의 `@Transactional` 이 담당한다.
 *
 * @param projectCreateService 프로젝트 생성 Application Service.
 * @param systemPermissionResolver 전역(시스템) 권한 판정 포트.
 */
@RestController
@RequestMapping("/api/v1/projects")
@PreAuthorize("isAuthenticated()")
class ProjectCreateController(
    private val projectCreateService: ProjectCreateApplicationService,
    private val systemPermissionResolver: SystemPermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 프로젝트를 생성하고 생성자를 PROJECT_ADMIN 으로 등록한다.
     *
     * @param request key·name 요청 바디 (Jakarta Validation — 정규식 위반 시 400, PJ1-6).
     * @return 201 Created + [CreateProjectResponse] body.
     * @throws org.springframework.web.server.ResponseStatusException 미인증 → 401 (CurrentActor).
     * @throws ProjectCreateForbiddenException CREATE_PROJECT 권한 없음 → 403.
     * @throws com.bts.issue.project.domain.ProjectKeyAlreadyExistsException key 중복 → 409.
     */
    @PostMapping
    fun createProject(
        @RequestBody @Valid request: CreateProjectRequest,
    ): ResponseEntity<DataResponse<CreateProjectResponse>> {
        // 1. actor 추출 — 리소스 조회보다 먼저 (미인증 401, PJ1-3)
        val actor = CurrentActor.current()

        // 2. 전역권한 게이트 — hasGlobalPermission (hasGrant 직접호출 금지)
        if (!systemPermissionResolver.hasGlobalPermission(actor.value, GlobalPermissionCodes.CREATE_PROJECT)) {
            throw ProjectCreateForbiddenException(actor.value)
        }

        log.info("ProjectCreateController.createProject actor={} key={}", actor.value, request.key)

        // 3. 생성 (같은 트랜잭션으로 생성자 PROJECT_ADMIN 등록까지 — 서비스 책임)
        val project = projectCreateService.create(actor.value, request.key, request.name)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(DataResponse(CreateProjectResponse.from(project)))
    }
}

/**
 * 프로젝트 생성 응답 DTO.
 *
 * @property id 생성된 프로젝트 UUID.
 * @property key 프로젝트 key.
 * @property name 프로젝트 이름.
 */
data class CreateProjectResponse(
    val id: UUID,
    val key: String,
    val name: String,
) {
    companion object {
        /**
         * [Project] 를 [CreateProjectResponse] 로 변환한다.
         *
         * @param project 생성된 프로젝트(id 는 DB 저장 후 non-null).
         * @return HTTP 응답 DTO.
         */
        fun from(project: Project): CreateProjectResponse =
            CreateProjectResponse(
                id = requireNotNull(project.id) { "생성된 project.id 는 null 일 수 없다" },
                key = project.key,
                name = project.name,
            )
    }
}

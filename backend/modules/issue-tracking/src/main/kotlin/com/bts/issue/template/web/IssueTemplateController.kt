// 이슈 템플릿 CRUD REST 컨트롤러 — POST/GET/PATCH/DELETE + resolve (FR-TM-01 Task 6)

package com.bts.issue.template.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.config.BEARER_AUTH_SCHEME
import com.bts.issue.project.ProjectLookup
import com.bts.issue.template.application.IssueTemplateApplicationService
import com.bts.issue.template.web.dto.CreateIssueTemplateRequest
import com.bts.issue.template.web.dto.IssueTemplateResponse
import com.bts.issue.template.web.dto.UpdateIssueTemplateRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** actorId placeholder — FR-PM-03 실 추출 이연. SecurityConfig 가 401 을 보장한다. */
private val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

/**
 * 이슈 템플릿 REST API 컨트롤러.
 *
 * 엔드포인트 목록 — 모두 `/api/v1/projects/{projectIdOrKey}/issue-templates` 하위.
 * - POST                              — 템플릿 생성 → 201
 * - GET                               — 활성 템플릿 목록 → 200
 * - GET  /{templateId}                — 단건 조회 → 200
 * - GET  /resolve?issueTypeId={id}    — (project, type) 활성 content 조회 → 200 또는 204
 * - PATCH /{templateId}               — name/content 수정 → 200
 * - DELETE /{templateId}              — 소프트 삭제 → 204
 *
 * ### projectIdOrKey 해석
 * path variable 은 UUID 문자열 또는 projectKey 문자열 둘 다 허용한다.
 * [ProjectLookup.resolve] 를 통해 활성 프로젝트 UUID 로 변환한다.
 * 미존재이면 404 [IssueTemplateErrorCodes.PROJECT_NOT_FOUND] 를 반환한다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 모든 트랜잭션은 [IssueTemplateApplicationService] 의 `@Transactional` 이 담당한다.
 *
 * ### actorId
 * FR-PM-03 이전까지 [SYSTEM_ACTOR_UUID] 를 placeholder 로 사용한다.
 * Security 필터가 인증 없는 요청에 401 을 보장하므로 서비스 레이어까지 도달하는
 * 요청은 인증된 사용자임이 보장된다.
 *
 * @param service 이슈 템플릿 CRUD Application Service.
 * @param projectLookup projectIdOrKey → 프로젝트 UUID 해석기.
 */
@Tag(name = "Issue Templates", description = "이슈 템플릿 CRUD 및 변수 치환 API (FR-TM-01/02)")
@RestController
@RequestMapping("/api/v1/projects/{projectIdOrKey}/issue-templates")
class IssueTemplateController(
    private val service: IssueTemplateApplicationService,
    private val projectLookup: ProjectLookup,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 소속 활성 이슈 템플릿 목록을 반환한다.
     *
     * `deleted_at IS NULL` 인 템플릿만 포함된다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @return 200 OK + `{ "data": [ ... ] }`.
     * @throws ResponseStatusException 404 — 프로젝트 미존재.
     */
    @Operation(operationId = "listIssueTemplates", summary = "이슈 템플릿 목록 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "템플릿 목록"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping
    fun list(
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<DataResponse<List<IssueTemplateResponse>>> {
        log.debug("IssueTemplateController.list projectIdOrKey={}", projectIdOrKey)
        val projectId = resolveProjectId(projectIdOrKey)
        val templates = service.listByProject(SYSTEM_ACTOR_UUID, projectId).map(IssueTemplateResponse::from)
        return ResponseEntity.ok(DataResponse(data = templates))
    }

    /**
     * 단건 이슈 템플릿을 조회한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param templateId path variable 템플릿 UUID.
     * @return 200 OK + [IssueTemplateResponse] body.
     * @throws com.bts.issue.template.domain.IssueTemplateNotFoundException 템플릿 미존재 → 404
     */
    @Operation(operationId = "getIssueTemplateById", summary = "이슈 템플릿 단건 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "템플릿"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "템플릿 또는 프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/{templateId}")
    fun getById(
        @PathVariable projectIdOrKey: String,
        @PathVariable templateId: UUID,
    ): ResponseEntity<DataResponse<IssueTemplateResponse>> {
        log.debug("IssueTemplateController.getById projectIdOrKey={} templateId={}", projectIdOrKey, templateId)
        resolveProjectId(projectIdOrKey)
        val template = service.getById(SYSTEM_ACTOR_UUID, templateId)
        return ResponseEntity.ok(DataResponse(data = IssueTemplateResponse.from(template)))
    }

    /**
     * (projectId, issueTypeId) 조합의 활성 템플릿 content 를 반환한다.
     *
     * 이슈 생성 프론트 프리필 용도. READ 미게이트.
     * 활성 템플릿이 존재하면 200 + `{ "content": "..." }`, 없으면 204.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param issueTypeId 조회할 이슈 타입 BIGINT.
     * @return 200 + content 또는 204 No Content.
     */
    @Operation(operationId = "resolveIssueTemplate", summary = "이슈 생성 프리필 템플릿 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "활성 템플릿 content"),
        ApiResponse(responseCode = "204", description = "활성 템플릿 없음"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/resolve")
    fun resolve(
        @PathVariable projectIdOrKey: String,
        @RequestParam issueTypeId: Long,
    ): ResponseEntity<Map<String, String>> {
        log.debug("IssueTemplateController.resolve projectIdOrKey={} issueTypeId={}", projectIdOrKey, issueTypeId)
        val projectId = resolveProjectId(projectIdOrKey)
        val content = service.resolve(projectId, issueTypeId)
        return if (content != null) {
            ResponseEntity.ok(mapOf("content" to content))
        } else {
            ResponseEntity.noContent().build()
        }
    }

    /**
     * 새 이슈 템플릿을 생성한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param request 이슈 템플릿 생성 요청 바디 (Jakarta Validation 적용).
     * @return 201 Created + [IssueTemplateResponse] body.
     * @throws ResponseStatusException 404 — 프로젝트 미존재.
     * @throws com.bts.issue.template.domain.DuplicateIssueTemplateException (project, type) 중복 → 409
     * @throws com.bts.issue.template.domain.IssueTemplateAccessDeniedException 권한 없음 → 403
     * @throws com.bts.issue.type.domain.IssueTypeNotFoundException issueType 미존재 → 404
     */
    @Operation(operationId = "createIssueTemplate", summary = "이슈 템플릿 생성")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "프로젝트 또는 이슈 타입 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "동일 (project, issueType) 템플릿 중복", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PostMapping
    fun create(
        @PathVariable projectIdOrKey: String,
        @Valid @RequestBody request: CreateIssueTemplateRequest,
    ): ResponseEntity<DataResponse<IssueTemplateResponse>> {
        log.info(
            "IssueTemplateController.create projectIdOrKey={} issueTypeId={} name={}",
            projectIdOrKey,
            request.issueTypeId,
            request.name,
        )
        val projectId = resolveProjectId(projectIdOrKey)
        val template =
            service.create(
                actorId = SYSTEM_ACTOR_UUID,
                projectId = projectId,
                issueTypeId = request.issueTypeId,
                name = request.name,
                content = request.content,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse(data = IssueTemplateResponse.from(template)))
    }

    /**
     * 이슈 템플릿의 name 과 content 를 수정한다.
     *
     * null 필드 = 무변경(sentinel 정책). issueTypeId 는 불변.
     * 도메인 [com.bts.issue.template.domain.IssueTemplate.withChanges] 가 불변식 검증을
     * 수행하므로 blank 값은 422 로 거부된다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param templateId path variable 템플릿 UUID.
     * @param request 수정 요청 바디.
     * @return 200 OK + 수정된 [IssueTemplateResponse] body.
     * @throws com.bts.issue.template.domain.IssueTemplateNotFoundException 템플릿 미존재 → 404
     * @throws com.bts.issue.template.domain.IssueTemplateAccessDeniedException 권한 없음 → 403
     * @throws com.bts.issue.template.domain.InvalidIssueTemplateException name/content 불변식 위반 → 422
     */
    @Operation(operationId = "updateIssueTemplate", summary = "이슈 템플릿 수정")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "템플릿 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PatchMapping("/{templateId}")
    fun update(
        @PathVariable projectIdOrKey: String,
        @PathVariable templateId: UUID,
        @Valid @RequestBody request: UpdateIssueTemplateRequest,
    ): ResponseEntity<DataResponse<IssueTemplateResponse>> {
        log.info("IssueTemplateController.update projectIdOrKey={} templateId={}", projectIdOrKey, templateId)
        val projectId = resolveProjectId(projectIdOrKey)
        val template =
            service.update(
                actorId = SYSTEM_ACTOR_UUID,
                projectId = projectId,
                templateId = templateId,
                name = request.name,
                content = request.content,
            )
        return ResponseEntity.ok(DataResponse(data = IssueTemplateResponse.from(template)))
    }

    /**
     * 이슈 템플릿을 소프트 삭제한다.
     *
     * 물리 삭제 금지 (DATA.md §3). `deleted_at` 를 현재 시각으로 설정한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param templateId path variable 템플릿 UUID.
     * @throws com.bts.issue.template.domain.IssueTemplateNotFoundException 템플릿 미존재 → 404
     * @throws com.bts.issue.template.domain.IssueTemplateAccessDeniedException 권한 없음 → 403
     */
    @Operation(operationId = "deleteIssueTemplate", summary = "이슈 템플릿 소프트 삭제")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "템플릿 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable projectIdOrKey: String,
        @PathVariable templateId: UUID,
    ) {
        log.info("IssueTemplateController.delete projectIdOrKey={} templateId={}", projectIdOrKey, templateId)
        val projectId = resolveProjectId(projectIdOrKey)
        service.delete(SYSTEM_ACTOR_UUID, projectId, templateId)
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * projectIdOrKey 를 활성 프로젝트 UUID 로 해석한다.
     *
     * [ProjectLookup.resolve] 가 null 을 반환하면 — 미존재 또는 소프트 삭제된 경우 —
     * 404 [ResponseStatusException] 을 던진다. 예외 핸들러가 캐치하기 전에
     * Spring MVC 가 처리하도록 [ResponseStatusException] 을 사용한다.
     *
     * @param projectIdOrKey UUID 문자열 또는 projectKey.
     * @return 활성 프로젝트 UUID.
     * @throws ResponseStatusException 404 — 프로젝트 미존재.
     */
    private fun resolveProjectId(projectIdOrKey: String): UUID =
        projectLookup.resolve(projectIdOrKey)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                IssueTemplateErrorCodes.PROJECT_NOT_FOUND,
            )
}

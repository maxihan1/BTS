// 컴포넌트 CRUD REST 컨트롤러 — POST/GET/PATCH/DELETE (FR-CM-01 Task 7)

package com.bts.issue.component.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.component.application.ComponentApplicationService
import com.bts.issue.component.web.dto.ChangeComponentLeadRequest
import com.bts.issue.component.web.dto.ComponentResponse
import com.bts.issue.component.web.dto.CreateComponentRequest
import com.bts.issue.component.web.dto.UpdateComponentRequest
import com.bts.issue.config.BEARER_AUTH_SCHEME
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** actorId placeholder — FR-PM-03 실 추출 이연. SecurityConfig 가 401 을 보장한다. */
private val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

/**
 * 컴포넌트 REST API 컨트롤러.
 *
 * 엔드포인트 목록 — 모두 `/api/v1/projects/{projectIdOrKey}/components` 하위.
 * - POST                    — 컴포넌트 생성 → 201
 * - GET                     — 활성 컴포넌트 목록 (name 오름차순) → 200
 * - GET  /{id}              — 단건 조회 → 200
 * - PATCH /{id}             — name/description 수정 → 200
 * - PATCH /{id}/lead        — 리드 지정 / 해제 (2-state) → 200
 * - DELETE /{id}            — 소프트 삭제 → 204
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 모든 트랜잭션은 [ComponentApplicationService] 의 `@Transactional` 이 담당한다.
 *
 * ### actorId
 * FR-PM-03 이전까지 [SYSTEM_ACTOR_UUID] 를 placeholder 로 사용한다.
 * Security 필터가 인증 없는 요청에 401 을 보장하므로 서비스 레이어까지 도달하는
 * 요청은 인증된 사용자임이 보장된다.
 *
 * @param service 컴포넌트 CRUD Application Service.
 */
@Tag(name = "Components", description = "프로젝트 컴포넌트 CRUD API (FR-CM-01)")
@RestController
@RequestMapping("/api/v1/projects/{projectIdOrKey}/components")
class ComponentController(
    private val service: ComponentApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 컴포넌트를 생성한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param request 컴포넌트 생성 요청 바디 (Jakarta Validation 적용).
     * @return 201 Created + [ComponentResponse] body.
     * @throws com.bts.issue.component.domain.ComponentProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.component.domain.ComponentLeadNotFoundException 리드 사용자 미존재 → 422
     * @throws com.bts.issue.component.domain.DuplicateComponentNameException 이름 중복 → 409
     */
    @Operation(operationId = "createComponent", summary = "컴포넌트 생성")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "프로젝트 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "컴포넌트명 중복", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PostMapping
    fun create(
        @PathVariable projectIdOrKey: String,
        @Valid @RequestBody request: CreateComponentRequest,
    ): ResponseEntity<DataResponse<ComponentResponse>> {
        log.info("ComponentController.create projectIdOrKey={} name={}", projectIdOrKey, request.name)
        val component =
            service.create(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                name = request.name,
                description = request.description,
                leadUserId = request.leadUserId,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse(data = ComponentResponse.from(component)))
    }

    /**
     * 프로젝트 소속 활성 컴포넌트 목록을 반환한다.
     *
     * `deleted_at IS NULL` 인 컴포넌트만 포함되며, name 오름차순으로 정렬된다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @return 200 OK + `{ "data": [ ... ] }`.
     * @throws com.bts.issue.component.domain.ComponentProjectNotFoundException 프로젝트 미존재 → 404
     */
    @Operation(operationId = "listComponents", summary = "컴포넌트 목록 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "컴포넌트 목록 (name 오름차순)"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping
    fun list(
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<DataResponse<List<ComponentResponse>>> {
        log.debug("ComponentController.list projectIdOrKey={}", projectIdOrKey)
        val components =
            service.listByProject(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
            ).map(ComponentResponse::from)
        return ResponseEntity.ok(DataResponse(data = components))
    }

    /**
     * 단건 컴포넌트를 조회한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param id path variable 컴포넌트 UUID.
     * @return 200 OK + [ComponentResponse] body.
     * @throws com.bts.issue.component.domain.ComponentProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.component.domain.ComponentNotFoundException 컴포넌트 미존재 → 404
     */
    @Operation(operationId = "getComponentById", summary = "컴포넌트 단건 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "컴포넌트"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "컴포넌트 또는 프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/{id}")
    fun getById(
        @PathVariable projectIdOrKey: String,
        @PathVariable id: UUID,
    ): ResponseEntity<DataResponse<ComponentResponse>> {
        log.debug("ComponentController.getById projectIdOrKey={} id={}", projectIdOrKey, id)
        val component =
            service.getById(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                componentId = id,
            )
        return ResponseEntity.ok(DataResponse(data = ComponentResponse.from(component)))
    }

    /**
     * 컴포넌트의 name / description 을 수정한다.
     *
     * null / 생략 = 무변경 (sentinel 정책). 리드 변경은 [changeLead] 전용 엔드포인트를 사용한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param id path variable 컴포넌트 UUID.
     * @param request 수정 요청 바디.
     * @return 200 OK + 수정된 [ComponentResponse] body.
     * @throws com.bts.issue.component.domain.ComponentProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.component.domain.ComponentNotFoundException 컴포넌트 미존재 → 404
     */
    @Operation(operationId = "updateComponent", summary = "컴포넌트 수정")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "컴포넌트 또는 프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PatchMapping("/{id}")
    fun update(
        @PathVariable projectIdOrKey: String,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateComponentRequest,
    ): ResponseEntity<DataResponse<ComponentResponse>> {
        log.info("ComponentController.update projectIdOrKey={} id={}", projectIdOrKey, id)
        val component =
            service.update(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                componentId = id,
                name = request.name,
                description = request.description,
            )
        return ResponseEntity.ok(DataResponse(data = ComponentResponse.from(component)))
    }

    /**
     * 컴포넌트 리드를 지정하거나 해제한다.
     *
     * [ChangeComponentLeadRequest.leadUserId] 가 null 이면 해제, UUID 이면 지정.
     * 2-state: IssueController.changeAssignee (/{key}/assignee) 와 동형 패턴.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param id path variable 컴포넌트 UUID.
     * @param request 리드 변경 요청 바디.
     * @return 200 OK + 변경된 [ComponentResponse] body.
     * @throws com.bts.issue.component.domain.ComponentProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.component.domain.ComponentNotFoundException 컴포넌트 미존재 → 404
     * @throws com.bts.issue.component.domain.ComponentLeadNotFoundException 리드 사용자 미존재 → 422
     */
    @Operation(operationId = "changeComponentLead", summary = "컴포넌트 리드 지정/해제")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "컴포넌트 또는 사용자 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PatchMapping("/{id}/lead")
    fun changeLead(
        @PathVariable projectIdOrKey: String,
        @PathVariable id: UUID,
        @RequestBody request: ChangeComponentLeadRequest,
    ): ResponseEntity<DataResponse<ComponentResponse>> {
        log.info(
            "ComponentController.changeLead projectIdOrKey={} id={} leadUserId={}",
            projectIdOrKey,
            id,
            request.leadUserId,
        )
        val component =
            service.changeLead(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                componentId = id,
                leadUserId = request.leadUserId,
            )
        return ResponseEntity.ok(DataResponse(data = ComponentResponse.from(component)))
    }

    /**
     * 컴포넌트를 소프트 삭제한다.
     *
     * 물리 삭제 금지 (DATA.md §3). `deleted_at` 를 현재 시각으로 설정한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param id path variable 컴포넌트 UUID.
     * @throws com.bts.issue.component.domain.ComponentProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.component.domain.ComponentNotFoundException 컴포넌트 미존재 → 404
     */
    @Operation(operationId = "deleteComponent", summary = "컴포넌트 소프트 삭제")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "컴포넌트 또는 프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable projectIdOrKey: String,
        @PathVariable id: UUID,
    ) {
        log.info("ComponentController.delete projectIdOrKey={} id={}", projectIdOrKey, id)
        service.delete(
            actorId = SYSTEM_ACTOR_UUID,
            projectIdOrKey = projectIdOrKey,
            componentId = id,
        )
    }
}

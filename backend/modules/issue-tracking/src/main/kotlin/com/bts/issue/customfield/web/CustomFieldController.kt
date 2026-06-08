// 커스텀 필드 정의 CRUD REST 컨트롤러 — POST/GET/PATCH/DELETE (FR-IS-10 Task 8)

package com.bts.issue.customfield.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.customfield.application.CustomFieldApplicationService
import com.bts.issue.customfield.domain.CustomFieldOption
import com.bts.issue.customfield.web.dto.CreateCustomFieldRequest
import com.bts.issue.customfield.web.dto.CustomFieldResponse
import com.bts.issue.customfield.web.dto.UpdateCustomFieldRequest
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
 * 커스텀 필드 정의 REST API 컨트롤러.
 *
 * 엔드포인트 목록 — 모두 `/api/v1/projects/{projectIdOrKey}/custom-fields` 하위.
 * - POST                        — 정의 생성 → 201
 * - GET                         — 활성 정의 목록 (display_order 오름차순) → 200
 * - GET  /{fieldId}             — 단건 조회 → 200
 * - PATCH /{fieldId}            — name/description/required/displayOrder/options 수정 → 200
 * - DELETE /{fieldId}           — 소프트 삭제 → 204
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 모든 트랜잭션은 [CustomFieldApplicationService] 의 `@Transactional` 이 담당한다.
 *
 * ### actorId
 * FR-PM-03 이전까지 [SYSTEM_ACTOR_UUID] 를 placeholder 로 사용한다.
 * Security 필터가 인증 없는 요청에 401 을 보장하므로 서비스 레이어까지 도달하는
 * 요청은 인증된 사용자임이 보장된다.
 *
 * @param service 커스텀 필드 정의 CRUD Application Service.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectIdOrKey}/custom-fields")
class CustomFieldController(
    private val service: CustomFieldApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 커스텀 필드 정의를 생성한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param request 커스텀 필드 생성 요청 바디 (Jakarta Validation 적용).
     * @return 201 Created + [CustomFieldResponse] body.
     * @throws com.bts.issue.customfield.domain.CustomFieldProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.customfield.domain.DuplicateCustomFieldKeyException key 중복 → 409
     * @throws com.bts.issue.customfield.domain.InvalidFieldDefinitionException 불변식 위반 → 422
     * @throws com.bts.issue.customfield.domain.CustomFieldAccessDeniedException 권한 없음 → 403
     */
    @PostMapping
    fun create(
        @PathVariable projectIdOrKey: String,
        @Valid @RequestBody request: CreateCustomFieldRequest,
    ): ResponseEntity<DataResponse<CustomFieldResponse>> {
        log.info(
            "CustomFieldController.create projectIdOrKey={} key={} fieldType={}",
            projectIdOrKey,
            request.key,
            request.fieldType,
        )
        val options =
            request.options.map { opt ->
                CustomFieldOption(
                    value = opt.value,
                    label = opt.label,
                    displayOrder = opt.displayOrder,
                )
            }
        val definition =
            service.create(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                key = request.key,
                name = request.name,
                fieldType = request.fieldType,
                required = request.required,
                displayOrder = request.displayOrder,
                options = options,
            )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(DataResponse(data = CustomFieldResponse.from(definition)))
    }

    /**
     * 프로젝트 소속 활성 커스텀 필드 정의 목록을 반환한다.
     *
     * `deleted_at IS NULL` 인 정의만 포함되며, display_order 오름차순으로 정렬된다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @return 200 OK + `{ "data": [ ... ] }`.
     * @throws com.bts.issue.customfield.domain.CustomFieldProjectNotFoundException 프로젝트 미존재 → 404
     */
    @GetMapping
    fun list(
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<DataResponse<List<CustomFieldResponse>>> {
        log.debug("CustomFieldController.list projectIdOrKey={}", projectIdOrKey)
        val definitions =
            service.listByProject(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
            ).map(CustomFieldResponse::from)
        return ResponseEntity.ok(DataResponse(data = definitions))
    }

    /**
     * 단건 커스텀 필드 정의를 조회한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param fieldId path variable 필드 정의 UUID.
     * @return 200 OK + [CustomFieldResponse] body.
     * @throws com.bts.issue.customfield.domain.CustomFieldProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.customfield.domain.CustomFieldNotFoundException 필드 정의 미존재 → 404
     */
    @GetMapping("/{fieldId}")
    fun getById(
        @PathVariable projectIdOrKey: String,
        @PathVariable fieldId: UUID,
    ): ResponseEntity<DataResponse<CustomFieldResponse>> {
        log.debug("CustomFieldController.getById projectIdOrKey={} fieldId={}", projectIdOrKey, fieldId)
        val definition =
            service.getById(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                fieldId = fieldId,
            )
        return ResponseEntity.ok(DataResponse(data = CustomFieldResponse.from(definition)))
    }

    /**
     * 커스텀 필드 정의의 name / description / required / displayOrder / options 를 수정한다.
     *
     * fieldType / key 는 생성 후 불변이다.
     * 변경을 시도하면 서비스 레이어에서 [com.bts.issue.customfield.domain.ImmutableFieldTypeChangeException] 이 발생한다.
     * null / 생략 = 무변경 (sentinel 정책).
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param fieldId path variable 필드 정의 UUID.
     * @param request 수정 요청 바디.
     * @return 200 OK + 수정된 [CustomFieldResponse] body.
     * @throws com.bts.issue.customfield.domain.CustomFieldProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.customfield.domain.CustomFieldNotFoundException 필드 정의 미존재 → 404
     * @throws com.bts.issue.customfield.domain.ImmutableFieldTypeChangeException fieldType/key 변경 시도 → 422
     * @throws com.bts.issue.customfield.domain.CustomFieldAccessDeniedException 권한 없음 → 403
     */
    @PatchMapping("/{fieldId}")
    fun update(
        @PathVariable projectIdOrKey: String,
        @PathVariable fieldId: UUID,
        @Valid @RequestBody request: UpdateCustomFieldRequest,
    ): ResponseEntity<DataResponse<CustomFieldResponse>> {
        log.info("CustomFieldController.update projectIdOrKey={} fieldId={}", projectIdOrKey, fieldId)
        val options =
            request.options?.map { opt ->
                com.bts.issue.customfield.domain.CustomFieldOption(
                    value = opt.value,
                    label = opt.label,
                    displayOrder = opt.displayOrder,
                )
            }
        val definition =
            service.update(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                fieldId = fieldId,
                name = request.name,
                description = request.description,
                fieldType = request.fieldType,
                key = request.key,
                required = request.required,
                displayOrder = request.displayOrder,
                options = options,
            )
        return ResponseEntity.ok(DataResponse(data = CustomFieldResponse.from(definition)))
    }

    /**
     * 커스텀 필드 정의를 소프트 삭제한다.
     *
     * 물리 삭제 금지 (DATA.md §3). `deleted_at` 를 현재 시각으로 설정한다.
     * 기존 이슈의 `custom_fields` JSONB 값은 보존되며, 조회 응답에서 비활성 정의 키만 제외된다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param fieldId path variable 필드 정의 UUID.
     * @throws com.bts.issue.customfield.domain.CustomFieldProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.customfield.domain.CustomFieldNotFoundException 필드 정의 미존재 → 404
     * @throws com.bts.issue.customfield.domain.CustomFieldAccessDeniedException 권한 없음 → 403
     */
    @DeleteMapping("/{fieldId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable projectIdOrKey: String,
        @PathVariable fieldId: UUID,
    ) {
        log.info("CustomFieldController.delete projectIdOrKey={} fieldId={}", projectIdOrKey, fieldId)
        service.softDelete(
            actorId = SYSTEM_ACTOR_UUID,
            projectIdOrKey = projectIdOrKey,
            fieldId = fieldId,
        )
    }
}
